package com.vettid.app.core.audit

import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest

/**
 * Verifies the vault's audit log signatures + hash chain client-side.
 *
 * Trust model:
 *   - The user's identity public key is the trust anchor (we already
 *     hold it; it's the same key that signs profiles, votes, contracts).
 *   - The audit log's `binding_sig` is identity_priv's signature over
 *     `"vettid-audit-binding-v1" || audit_pub`. Verifying this against
 *     identity_pub proves audit_pub is bound to the user — even if an
 *     attacker recovered audit_priv they couldn't forge the binding.
 *   - Each row carries `entry_sig` = ed25519_sign(audit_priv, entry_hash).
 *     Verifying against audit_pub proves the row was written by the
 *     audit key (and thus by the vault while it had the identity key).
 *   - `entry_hash` is deterministic over (previous_hash, event_id,
 *     event_type, source_id, encrypted_payload, created_at). Walking
 *     the chain catches insertion / deletion / reorder of any row.
 *
 * The vault sends encrypted_payload as part of the chain input, but the
 * client doesn't have the DEK to recompute the payload locally — so the
 * client trusts the vault's reported `entry_hash` and only checks the
 * signature + the chain linkage (prev_hash continuity). That's the
 * right tradeoff for a privacy-first system: the vault is the only
 * thing that can decrypt, so it's the only thing that *could* produce
 * a valid signature; we just have to confirm the signature is real and
 * the chain isn't reordered.
 */
class AuditChainVerifier {

    /** Result of verifying a single audit row. */
    enum class RowState {
        /** Row predates the chain shipment or was written pre-PIN-unlock. */
        Unsigned,
        /** Signature verified against bound audit_pub; chain linkage intact. */
        Verified,
        /** Signature failed, chain broke, or audit_pub binding failed. */
        Tampered,
    }

    /** Per-row verification result returned alongside the original row. */
    data class RowVerification(
        val rowIndex: Int,
        val state: RowState,
        val reason: String? = null,
    )

    /** Aggregate chain status surfaced as a screen-level pill. */
    sealed class ChainStatus {
        object Empty : ChainStatus()
        data class Verified(val signedRows: Int, val unsignedRows: Int) : ChainStatus()
        data class Unsigned(val rows: Int) : ChainStatus()
        data class Tampered(val firstBadRowIndex: Int, val reason: String) : ChainStatus()
    }

    private val bindingDomain = "vettid-audit-binding-v1"

    /**
     * Verify a list of rows in newest-first order (the natural query
     * shape). The chain check walks from oldest → newest, so the input
     * is reversed internally. Returns (per-row results, overall chain
     * status).
     *
     * @param rows           rows from the vault response, newest-first
     * @param auditPubB64    base64 audit pub key (from response anchor)
     * @param bindingSigB64  base64 binding signature (from response anchor)
     * @param identityPub    raw 32-byte identity public key (from credential)
     * @param entryHashOf    function pulling (entryHash, prevHash, entrySig)
     *                       from one of the rows. The chain types are
     *                       different shapes (FeedEvent vs AuditEntry)
     *                       so we don't take the type directly here.
     */
    fun <T> verifyChain(
        rows: List<T>,
        auditPubB64: String?,
        bindingSigB64: String?,
        identityPub: ByteArray?,
        entryHashOf: (T) -> Triple<String?, String?, String?>,
    ): Pair<List<RowVerification>, ChainStatus> {
        if (rows.isEmpty()) {
            return emptyList<RowVerification>() to ChainStatus.Empty
        }

        val auditPub = auditPubB64?.takeIf { it.isNotBlank() }?.let { decodeB64Safe(it) }
        val bindingSig = bindingSigB64?.takeIf { it.isNotBlank() }?.let { decodeB64Safe(it) }

        // If we don't have an anchor or identity pub, every signed row
        // can't be verified — treat them as "Unsigned" pending the next
        // PIN unlock that produces an anchor.
        val anchorVerified = if (auditPub != null && bindingSig != null && identityPub != null) {
            verifyEd25519(
                pub = identityPub,
                msg = bindingDomain.toByteArray() + auditPub,
                sig = bindingSig,
            )
        } else false

        if (!anchorVerified) {
            val results = rows.mapIndexed { i, _ ->
                RowVerification(i, RowState.Unsigned, "no verified audit anchor")
            }
            val anyChainRow = rows.any { entryHashOf(it).first?.isNotBlank() == true }
            return results to if (anyChainRow) ChainStatus.Unsigned(rows.size) else ChainStatus.Empty
        }

        // Walk oldest → newest so prev_hash continuity is checkable.
        // The "expected" previous_hash for row N is the entry_hash of
        // row N-1. The first row's expected prev_hash is "genesis"
        // OR matches whatever the row reports (we don't require
        // genesis explicitly since the chain can extend earlier than
        // the page we received).
        val orderedOldFirst = rows.asReversed()
        val perRow = Array(rows.size) { RowVerification(it, RowState.Unsigned) }
        var prevHash: String? = null
        var chainStatus: ChainStatus = ChainStatus.Verified(0, 0)
        var signedCount = 0
        var unsignedCount = 0

        for ((idxOld, row) in orderedOldFirst.withIndex()) {
            val (entryHash, prevHashReported, entrySig) = entryHashOf(row)
            val newestFirstIdx = rows.size - 1 - idxOld

            if (entryHash.isNullOrBlank() && entrySig.isNullOrBlank()) {
                // Pre-chain legacy row. Don't claim Verified, but
                // don't claim Tampered either — chain just doesn't
                // exist for this row.
                perRow[newestFirstIdx] = RowVerification(newestFirstIdx, RowState.Unsigned, "no chain fields")
                unsignedCount++
                continue
            }

            // Chain linkage: prevHashReported should match previous
            // row's entry_hash (or be empty/genesis for the first
            // row we've seen on this page).
            if (prevHash != null && !prevHashReported.isNullOrBlank() &&
                prevHashReported != prevHash) {
                chainStatus = ChainStatus.Tampered(
                    firstBadRowIndex = newestFirstIdx,
                    reason = "previous_hash mismatch at row $newestFirstIdx",
                )
                perRow[newestFirstIdx] = RowVerification(newestFirstIdx, RowState.Tampered, "previous_hash mismatch")
                // Mark all newer rows as suspect too — break the loop
                // so the screen status reflects the earliest tamper.
                for (j in 0 until newestFirstIdx) {
                    perRow[j] = RowVerification(j, RowState.Tampered, "downstream of tampered row")
                }
                return perRow.toList() to chainStatus
            }

            // Signature check: entry_sig should be a valid ed25519
            // signature over entry_hash by audit_pub.
            if (!entrySig.isNullOrBlank() && auditPub != null) {
                val sigBytes = hexDecode(entrySig)
                val msgBytes = entryHash?.toByteArray() ?: byteArrayOf()
                if (verifyEd25519(auditPub, msgBytes, sigBytes)) {
                    perRow[newestFirstIdx] = RowVerification(newestFirstIdx, RowState.Verified)
                    signedCount++
                } else {
                    chainStatus = ChainStatus.Tampered(
                        firstBadRowIndex = newestFirstIdx,
                        reason = "entry_sig invalid at row $newestFirstIdx",
                    )
                    perRow[newestFirstIdx] = RowVerification(newestFirstIdx, RowState.Tampered, "entry_sig invalid")
                    for (j in 0 until newestFirstIdx) {
                        perRow[j] = RowVerification(j, RowState.Tampered, "downstream of tampered row")
                    }
                    return perRow.toList() to chainStatus
                }
            } else {
                perRow[newestFirstIdx] = RowVerification(newestFirstIdx, RowState.Unsigned, "no entry_sig")
                unsignedCount++
            }

            prevHash = entryHash
        }

        return perRow.toList() to when {
            signedCount > 0 -> ChainStatus.Verified(signedCount, unsignedCount)
            unsignedCount > 0 -> ChainStatus.Unsigned(unsignedCount)
            else -> ChainStatus.Empty
        }
    }

    private fun verifyEd25519(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pub.size != 32 || sig.size != 64) return false
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(pub, 0))
            signer.update(msg, 0, msg.size)
            signer.verifySignature(sig)
        } catch (e: Exception) {
            Log.w(TAG, "ed25519 verify failed: ${e.message}")
            false
        }
    }

    // Standard b64 first (vault always uses Go's base64.StdEncoding for
    // these fields), URL_SAFE only as a fallback. Prior order tried
    // URL_SAFE first, which silently produced SHORTER output on inputs
    // containing `+` or `/` — those chars aren't in the URL_SAFE alphabet
    // but Android's decoder didn't fail loudly, just dropped them. al's
    // binding_sig had four such chars and three bytes vanished, breaking
    // anchor verification on his vault while mesmer's (only `/`s) survived.
    // Surfaced 2026-05-16.
    private fun decodeB64Safe(s: String): ByteArray? = try {
        Base64.decode(s, Base64.NO_WRAP) ?: Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE)
    } catch (_: Exception) {
        try { Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE) } catch (_: Exception) { null }
    }

    private fun hexDecode(hex: String): ByteArray {
        if (hex.length % 2 != 0) return byteArrayOf()
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            if (high < 0 || low < 0) return byteArrayOf()
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    @Suppress("unused")
    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    companion object {
        private const val TAG = "AuditChainVerifier"
    }
}
