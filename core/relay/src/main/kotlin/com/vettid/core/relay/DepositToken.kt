package com.vettid.core.relay

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonNumber
import com.vettid.core.crypto.json.JsonString
import com.vettid.core.crypto.json.StrictJson
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** A token's optional deposit cap (RELAY-PROTOCOL §5.2). */
data class Quota(val msgs: Long? = null, val bytes: Long? = null)

/** Deposit-token claims (RELAY-PROTOCOL §5.2). */
data class TokenClaims(
    /** The issuing (recipient) mailbox id. */
    val iss: String,
    /** The sender's base64 relay key, or `*` for an open token. */
    val sub: String,
    /** The relay base URL, matched exactly. */
    val aud: String,
    val iat: Instant,
    val exp: Instant,
    val jti: String,
    val scope: String,
    val quota: Quota? = null,
) {
    val isOpen: Boolean get() = scope == SCOPE_DEPOSIT_OPEN

    /** The sender's key for a sender-bound token. */
    val subKey: ByteArray? get() = if (isOpen) null else RelayAuth.decodeKey(sub)

    /** Compact JSON in spec key order (iss, sub, aud, iat, exp, jti, scope[, quota]), as the relay's minter writes it. */
    fun toJson(): String {
        val b = JsonBuilder().string("iss", iss).string("sub", sub).string("aud", aud)
            .string("iat", rfc3339.format(iat)).string("exp", rfc3339.format(exp)).string("jti", jti).string("scope", scope)
        quota?.let { q ->
            val qb = JsonBuilder()
            q.msgs?.let { qb.uint("msgs", it) }
            q.bytes?.let { qb.uint("bytes", it) }
            b.raw("quota", qb.build())
        }
        return b.build()
    }

    companion object {
        const val SCOPE_DEPOSIT = "deposit"
        const val SCOPE_DEPOSIT_OPEN = "deposit_open"
        const val OPEN_SUB = "*"
        const val MAX_JTI_LEN = 128
        private val rfc3339 = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        /** Decodes and structurally validates claim JSON (unknown claims are ignored). */
        @Suppress("CyclomaticComplexMethod")
        fun parse(m: ByteArray): TokenClaims {
            val o = try {
                StrictJson.parseObject(m)
            } catch (_: CryptoException) {
                throw TokenException.Invalid()
            }
            fun str(n: String): String = (o[n] as? JsonString)?.value ?: throw TokenException.Invalid()
            val c = TokenClaims(
                iss = str("iss"), sub = str("sub"), aud = str("aud"), iat = time(str("iat")), exp = time(str("exp")),
                jti = str("jti"), scope = str("scope"),
                quota = o.optObj("quota")?.let { q ->
                    fun n(
                        k: String,
                    ): Long? = (q[k] as? JsonNumber)?.raw?.toLongOrNull()?.also { if (it < 0) throw TokenException.Invalid() }
                    Quota(n("msgs"), n("bytes"))
                },
            )
            when (c.scope) {
                SCOPE_DEPOSIT -> RelayAuth.decodeKey(c.sub) ?: throw TokenException.Invalid()
                SCOPE_DEPOSIT_OPEN -> if (c.sub != OPEN_SUB) throw TokenException.Invalid()
                else -> throw TokenException.Invalid()
            }
            if (c.jti.isEmpty() || c.jti.length > MAX_JTI_LEN || !RelayAuth.isValidMailboxId(c.iss)) throw TokenException.Invalid()
            return c
        }

        private fun time(s: String): Instant = try {
            OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            throw TokenException.Invalid()
        }
    }
}

/**
 * Minting and checking deposit tokens (RELAY-PROTOCOL §5). The recipient
 * mints with its own relay key; `iat` is backdated by up to 60 s (§5.2,
 * 0.4.0) so that a clock running ahead of the relay's does not make fresh
 * tokens `token_expired`; lifetimes run from the backdated `iat`.
 */
object DepositTokens {
    /** §5.2: issuers backdate `iat` by up to 60 s. */
    val BACKDATE: Duration = Duration.ofSeconds(60)

    /** Standing tokens: about 30 days (§5.2). */
    val STANDING_TTL: Duration = Duration.ofDays(30)

    fun mint(key: Ed25519PrivateKey, claims: TokenClaims): String = Paseto.sign(key, claims.toJson().toByteArray(Charsets.UTF_8))

    /** A sender-bound token for [senderB64] to deposit into [key]'s mailbox on the relay [audience]. */
    fun mintStanding(
        key: Ed25519PrivateKey,
        senderB64: String,
        audience: String,
        now: Instant,
        ttl: Duration = STANDING_TTL,
        jti: String? = null,
        quota: Quota? = null,
    ): String {
        val iat = now.truncatedTo(ChronoUnit.SECONDS).minus(BACKDATE)
        return mint(
            key,
            TokenClaims(
                RelayAuth.mailboxId(key.publicKey), senderB64, audience, iat, iat.plus(ttl), jti ?: Ulid.new(now),
                TokenClaims.SCOPE_DEPOSIT, quota,
            ),
        )
    }

    /**
     * A one-shot open token (§5.6): one deposit, signed with any key, which
     * becomes the message's sender. `iat` is backdated by at most half the
     * lifetime.
     */
    fun mintOpen(
        key: Ed25519PrivateKey,
        audience: String,
        now: Instant,
        ttl: Duration = Duration.ofMinutes(5),
        jti: String? = null,
    ): String {
        val half = ttl.dividedBy(2)
        val iat = now.truncatedTo(ChronoUnit.SECONDS).minus(if (BACKDATE < half) BACKDATE else half)
        return mint(
            key,
            TokenClaims(
                RelayAuth.mailboxId(key.publicKey), TokenClaims.OPEN_SUB, audience, iat, iat.plus(ttl), jti ?: Ulid.new(now),
                TokenClaims.SCOPE_DEPOSIT_OPEN,
            ),
        )
    }

    /** Verifies [token] against its issuer's relay key and parses its claims (§5.3 step 2). */
    fun verify(token: String, issuerPub: ByteArray): TokenClaims = TokenClaims.parse(Paseto.verify(token, issuerPub))
}
