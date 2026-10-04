package com.vettid.core.attestation.manifest

import com.vettid.core.attestation.AttestationException
import com.vettid.core.attestation.nitro.Measurements
import com.vettid.core.attestation.nitro.NitroVerifier
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.altchan.Descriptor
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import java.net.URI
import java.net.URISyntaxException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant

/** Release statuses (§11.10.1), fixed from release 1 on. */
enum class ReleaseStatus(val wire: String) {
    ACTIVE("active"),
    DEPRECATED("deprecated"),
    RETIRED("retired"),
    REMOVED("removed"),
    ;

    companion object {
        fun of(s: String): ReleaseStatus = entries.firstOrNull { it.wire == s } ?: throw AttestationException.Format("manifest status")
    }
}

/** One manifest entry. */
data class Release(
    val number: Long,
    val pcr0: String,
    val pcr1: String,
    val pcr2: String,
    val sealKey: String,
    val status: ReleaseStatus,
    val publishedAt: Instant,
    /** The release's end date (0.10.0, optional). */
    val endsAt: Instant?,
    val notes: String,
) {
    val measurements: Measurements get() = Measurements(pcr0, pcr1, pcr2)
}

/**
 * VettID's signed release manifest (VAULT-MESSAGING §11.10.1, 0.10.0), parsed
 * strictly from its exact bytes: compact JSON with the §5.3 rules, at most
 * 65,536 bytes, entries sorted by unique release number, unique PCR0s,
 * lowercase non-debug PCRs, known statuses, https notes and well-formed
 * `ends_at` wherever present. Ported from vettid-vault `vms/manifest`.
 */
class ReleaseManifest private constructor(
    val serial: Long,
    val issuedAt: Instant,
    val releases: List<Release>,
    private val raw: ByteArray,
) {
    /** The exact signed bytes. */
    fun bytes(): ByteArray = raw.copyOf()

    /** hex(SHA-256(manifest bytes)): a request's `manifest_sha256` and the bucket object name. */
    val sha256Hex: String get() = Bytes.hex(Bytes.sha256(raw))

    fun byPcr0(pcr0: String): Release? = releases.firstOrNull { Bytes.constantTimeEquals(it.pcr0.toByteArray(), pcr0.toByteArray()) }

    fun byNumber(n: Long): Release? = releases.firstOrNull { it.number == n }

    /** The active entry with the highest release number. */
    fun newest(): Release? = releases.lastOrNull { it.status == ReleaseStatus.ACTIVE }

    /** Refuses a manifest older than one already seen (§11.10.1). */
    fun checkSerial(seen: Long) {
        if (serial < seen) throw AttestationException.ManifestSerial()
    }

    companion object {
        const val LABEL = "vettid/pcr-manifest/1"
        const val MAX_BYTES = 65_536
        const val MAX_SERVED = 90_112
        private const val MAX_SEAL_KEY = 256
        private const val MAX_NOTES = 1024

        @Suppress("CyclomaticComplexMethod")
        fun parse(b: ByteArray): ReleaseManifest {
            if (b.isEmpty() || b.size > MAX_BYTES) throw AttestationException.Format("manifest size")
            try {
                val o = StrictJson.parseObject(b)
                if (StrictJson.compact(b).size != b.size) throw AttestationException.Format("manifest not compact")
                o.uint("v", 1, 1)
                val serial = o.uint("serial", 1, StrictJson.MAX_SAFE_INTEGER)
                val issuedAt = Timestamps.parseSeconds(o.string("issued_at"))
                val arr = o.array("releases")
                if (arr.isEmpty()) throw AttestationException.Format("manifest releases")
                val out = ArrayList<Release>()
                val seen = HashSet<String>()
                for (raw in arr) {
                    val r = parseRelease(raw.asObject())
                    if (out.isNotEmpty() && r.number <= out.last().number) throw AttestationException.Format("manifest order")
                    if (!seen.add(r.pcr0)) throw AttestationException.Format("manifest duplicate pcr0")
                    out.add(r)
                }
                return ReleaseManifest(serial, issuedAt, out, b.copyOf())
            } catch (_: CryptoException) {
                throw AttestationException.Format("manifest")
            }
        }

        private fun parseRelease(e: JsonObject): Release {
            val r = Release(
                number = e.uint("release", 1, StrictJson.MAX_SAFE_INTEGER),
                pcr0 = e.string("pcr0"),
                pcr1 = e.string("pcr1"),
                pcr2 = e.string("pcr2"),
                sealKey = e.string("seal_key"),
                status = ReleaseStatus.of(e.string("status")),
                publishedAt = Timestamps.parseSeconds(e.string("published_at")),
                endsAt = e.optString("ends_at")?.let { Timestamps.parseSeconds(it) },
                notes = e.string("notes"),
            )
            if (!Descriptor.isValidPcr(r.pcr0) || !Descriptor.isValidPcr(r.pcr1) || !Descriptor.isValidPcr(r.pcr2)) {
                throw AttestationException.Format("manifest pcr")
            }
            if (r.sealKey.isEmpty() || r.sealKey.length > MAX_SEAL_KEY || r.sealKey.any { it.code !in 0x21..0x7e }) {
                throw AttestationException.Format("manifest seal_key")
            }
            if (!validNotes(r.notes)) throw AttestationException.Format("manifest notes")
            return r
        }

        private fun validNotes(s: String): Boolean {
            if (s.isEmpty() || s.length > MAX_NOTES) return false
            return try {
                val u = URI(s)
                u.scheme == "https" && !u.host.isNullOrEmpty() && u.rawUserInfo == null
            } catch (_: URISyntaxException) {
                false
            }
        }

        /** SHA-256("vettid/pcr-manifest/1" || 0x00 || manifest): what the signature covers. */
        fun signedDigest(manifest: ByteArray): ByteArray = Bytes.sha256(LABEL.toByteArray(), byteArrayOf(0), manifest)
    }
}

/** The served document `{manifest: b64, sig: b64 r||s, key_id: 16 hex}` (§11.10.1). */
class ServedManifest private constructor(private val manifest: ByteArray, private val sig: ByteArray, val keyId: String) {
    fun manifestBytes(): ByteArray = manifest.copyOf()

    fun sig(): ByteArray = sig.copyOf()

    companion object {
        fun parse(raw: ByteArray): ServedManifest {
            if (raw.isEmpty() || raw.size > ReleaseManifest.MAX_SERVED) throw AttestationException.Format("served manifest size")
            try {
                val o = StrictJson.parseObject(raw)
                val m = o.base64("manifest")
                if (m.isEmpty() || m.size > ReleaseManifest.MAX_BYTES) throw AttestationException.Format("served manifest")
                val keyId = o.string("key_id")
                if (!Bytes.isLowerHex(keyId, 16)) throw AttestationException.Format("served manifest key_id")
                return ServedManifest(m, o.base64("sig", 64), keyId)
            } catch (_: CryptoException) {
                throw AttestationException.Format("served manifest")
            }
        }
    }
}

/** A pinned manifest public key (ECDSA P-256), given as its SubjectPublicKeyInfo DER. */
class ManifestKey(spki: ByteArray) {
    private val spki = spki.copyOf()

    /** hex(SHA-256(SPKI DER)[0:8]). */
    val keyId: String = Bytes.hex(Bytes.sha256(spki).copyOf(8))

    internal val publicKey: PublicKey = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
    } catch (_: GeneralSecurityException) {
        throw AttestationException.Format("manifest key")
    }

    companion object {
        fun fromBase64(spkiB64: String) = ManifestKey(Base64s.decodeStd(spkiB64))
    }
}

/** Verifies served manifests against the pinned keys (§11.10.1). */
class ManifestVerifier(private val pinned: List<ManifestKey>) {
    /** Checks the signature under the pinned key `key_id` selects, then parses the manifest strictly. */
    fun verify(s: ServedManifest): ReleaseManifest {
        val key = pinned.firstOrNull { Bytes.constantTimeEquals(it.keyId.toByteArray(), s.keyId.toByteArray()) }
            ?: throw AttestationException.ManifestKey()
        val m = s.manifestBytes()
        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key.publicKey)
                update(ReleaseManifest.LABEL.toByteArray())
                update(0)
                update(m)
                verify(NitroVerifier.rawToDer(s.sig()))
            }
        } catch (_: GeneralSecurityException) {
            false
        }
        if (!ok) throw AttestationException.Signature("manifest")
        return ReleaseManifest.parse(m)
    }

    fun verify(served: ByteArray): ReleaseManifest = verify(ServedManifest.parse(served))

    /**
     * Verifies a served document that a request names by hash and serial
     * (0.10.0): it parses, SHA-256 of its manifest equals [sha256Hex], its
     * signature verifies under a pinned key, the manifest parses strictly, and
     * its serial equals [serial].
     */
    fun verifyByHash(doc: ByteArray, sha256Hex: String, serial: Long): ReleaseManifest {
        val s = ServedManifest.parse(doc)
        val got = Bytes.hex(Bytes.sha256(s.manifestBytes()))
        if (!Bytes.isLowerHex(sha256Hex, 64) || !Bytes.constantTimeEquals(got.toByteArray(), sha256Hex.toByteArray())) {
            throw AttestationException.Hash()
        }
        val m = verify(s)
        if (m.serial != serial) throw AttestationException.Hash()
        return m
    }
}
