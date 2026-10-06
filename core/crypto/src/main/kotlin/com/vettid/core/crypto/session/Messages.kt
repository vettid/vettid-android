package com.vettid.core.crypto.session

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.altchan.DeviceAttest
import com.vettid.core.crypto.hpke.KemPublicKey
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import com.vettid.core.crypto.json.asUint
import java.net.URI
import java.net.URISyntaxException

/** hs.init purposes (§6.2). */
enum class Purpose(val wire: String) {
    APP("app"),
    DESKTOP("desktop"),
    AGENT("agent"),
    CONNECTION("connection"),
    REKEY("rekey"),
    RECONNECT("reconnect"),
    ;

    val isPairing: Boolean get() = this == APP || this == DESKTOP || this == AGENT

    /**
     * Whether a handshake of this purpose has a SAS, and so the commitment
     * fields (§6.2, 0.10.3): app, desktop, agent and connection. Rekeys and
     * reconnects carry none.
     */
    val hasSas: Boolean get() = isPairing || this == CONNECTION

    companion object {
        fun of(s: String): Purpose = entries.firstOrNull { it.wire == s } ?: throw CryptoException.Format("purpose")
    }
}

/** A relay address `{url, mailbox, pk}` (§6.2). */
class RelayAddr(val url: String, val mailbox: String, pk: ByteArray) {
    private val pk = pk.copyOf()

    fun pk(): ByteArray = pk.copyOf()

    /** Validates: https base URL (http only for loopback), 32-byte key, mailbox derived from it. */
    fun validate() {
        if (pk.size != Suite.ED25519_PUBLIC_SIZE || mailbox != Mailbox.id(pk)) throw CryptoException.Format("relay address")
        Relay.validateUrl(url)
    }

    internal fun marshal(): String = JsonBuilder().string("url", url).string("mailbox", mailbox).base64("pk", pk).build()

    companion object {
        internal fun parse(o: JsonObject): RelayAddr =
            RelayAddr(o.string("url"), o.string("mailbox"), o.base64("pk", Suite.ED25519_PUBLIC_SIZE)).also { it.validate() }
    }
}

/** A principal `{ik, kem, relay}`: hs.init `from`, a bundle's `vault`. */
class Principal(ik: ByteArray, val kem: KemPublicKey, val relay: RelayAddr) {
    private val ik = ik.copyOf()

    fun ik(): ByteArray = ik.copyOf()

    internal fun validate() {
        if (ik.size != Suite.ED25519_PUBLIC_SIZE) throw CryptoException.Format("principal ik")
        relay.validate()
    }

    fun marshal(): String = JsonBuilder()
        .base64("ik", ik)
        .base64("kem", kem.bytes())
        .raw("relay", relay.marshal())
        .build()

    companion object {
        fun parse(o: JsonObject): Principal = Principal(
            o.base64("ik", Suite.ED25519_PUBLIC_SIZE),
            KemPublicKey.parse(o.base64("kem", Suite.EK_SIZE)),
            RelayAddr.parse(o.obj("relay")),
        )
    }
}

/** Relay mailbox ids (RELAY-PROTOCOL §3.2). */
object Mailbox {
    const val LENGTH = 26
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    /** lowercase base32(SHA-256(pk))[0:26]. */
    fun id(pk: ByteArray): String {
        val h = Bytes.sha256(pk)
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (x in h) {
            buffer = (buffer shl 8) or (x.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        return sb.substring(0, LENGTH)
    }
}

/** Relay URL and token shapes (§6.2). */
object Relay {
    const val MAX_URL_LEN = 256
    const val MAX_TOKEN_LEN = 4096
    private const val TOKEN_PREFIX = "v4.public."

    /**
     * An absolute `https` base URL without user info, query or fragment;
     * `http` only for loopback hosts (development).
     */
    @Suppress("CyclomaticComplexMethod")
    fun validateUrl(s: String) {
        if (s.isEmpty() || s.length > MAX_URL_LEN || s.any { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) {
            throw CryptoException.Format("relay url")
        }
        val u = try {
            URI(s)
        } catch (_: URISyntaxException) {
            throw CryptoException.Format("relay url")
        }
        if (u.isOpaque || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null || u.host.isNullOrEmpty()) {
            throw CryptoException.Format("relay url")
        }
        when (u.scheme) {
            "https" -> Unit
            "http" -> if (!isLoopback(u.host)) throw CryptoException.Format("relay url")
            else -> throw CryptoException.Format("relay url")
        }
    }

    private fun isLoopback(host: String): Boolean {
        if (host == "localhost" || host == "[::1]") return true
        val parts = host.split('.')
        return parts.size == 4 && parts[0] == "127" && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    /** The shape of a PASETO v4.public token (the relay verifies it). */
    fun isValidToken(s: String): Boolean =
        s.length > TOKEN_PREFIX.length && s.length <= MAX_TOKEN_LEN && s.startsWith(TOKEN_PREFIX) &&
            s.substring(TOKEN_PREFIX.length).all(::isTokenChar)

    private fun isTokenChar(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.'
}

/** Field limits of the handshake bodies. */
internal object HsLimits {
    const val MAX_CTX_LEN = 128
    const val MAX_ROTATIONS = 32
    const val MAX_OPAQUE_JSON = 16 * 1024

    /** n_I, n_R and sas_commit are 32 bytes (§6.2). */
    const val SAS_NONCE_SIZE = 32
}

// Token rules per purpose: 1 required, 0 optional, -1 forbidden (§6.2). A
// connection handshake carries a request token and no reconnect_token, which
// follows in connection.approved (0.10.3).
private fun tokenRules(p: Purpose): Pair<Int, Int> = when (p) {
    Purpose.APP, Purpose.DESKTOP, Purpose.AGENT, Purpose.CONNECTION -> 1 to -1
    Purpose.RECONNECT -> 1 to 1
    Purpose.REKEY -> 0 to 0
}

// The commitment rule of §6.2 (0.10.3): a 32-byte value exactly for purposes with a SAS, absent otherwise.
private fun checkSasField(p: Purpose, v: ByteArray?, what: String) {
    if (p.hasSas != (v != null) || (v != null && v.size != HsLimits.SAS_NONCE_SIZE)) throw CryptoException.Format(what)
}

private fun optSasField(o: JsonObject, name: String): ByteArray? =
    if (o.has(name)) o.base64(name, HsLimits.SAS_NONCE_SIZE) else null

private fun checkTokenRule(rule: Int, v: String?) {
    when {
        v == null && rule == 1 -> throw CryptoException.Format("token required")
        v != null && rule == -1 -> throw CryptoException.Format("token forbidden")
        v != null && !Relay.isValidToken(v) -> throw CryptoException.Format("token")
    }
}

private fun validCtx(s: String): Boolean = s.isNotEmpty() && s.length <= HsLimits.MAX_CTX_LEN && s.all { it.code in 0x21..0x7e }

private fun optToken(o: JsonObject, name: String): String? {
    val t = o.optString(name)
    if (t != null && t.isEmpty()) throw CryptoException.Format("token")
    return t
}

private fun parseRotations(o: JsonObject): List<Rotation>? {
    val arr = o.optArray("rotations") ?: return null
    if (arr.size > HsLimits.MAX_ROTATIONS) throw CryptoException.Format("rotations")
    return arr.map { Rotation.parse(it.asObject()) }
}

private fun marshalRotations(rs: List<Rotation>): String = JsonBuilder.array(rs.map { it.marshal() })

/** The hs.init body (§6.2). */
class HsInit(
    val purpose: Purpose,
    val ctx: String,
    val from: Principal,
    val eph: KemPublicKey,
    val token: String? = null,
    val reconnectToken: String? = null,
    val suites: List<Int> = listOf(Suite.SUITE_2),
    /** Self-asserted profile object JSON (§6.4), compacted. */
    val profile: ByteArray? = null,
    val rotations: List<Rotation> = emptyList(),
    val deviceAttest: DeviceAttest? = null,
    /**
     * The new app's app key (§6.2, §11.12, 0.15.0): the SPKI DER of a P-256 key, sent as standard base64, only in
     * a transfer's `hs.init` (purpose app); absent otherwise.
     */
    apiKey: ByteArray? = null,
    /** SHA-256("vettid/vms/2/sas-commit" || n_I), exactly for purposes with a SAS (§6.2, §6.3, 0.10.3). */
    sasCommit: ByteArray? = null,
) {
    private val sasCommit = sasCommit?.copyOf()
    private val apiKey = apiKey?.copyOf()

    fun sasCommit(): ByteArray? = sasCommit?.copyOf()

    fun apiKey(): ByteArray? = apiKey?.copyOf()

    @Suppress("CyclomaticComplexMethod")
    internal fun validate() {
        if (!validCtx(ctx)) throw CryptoException.Format("ctx")
        from.validate()
        if (eph == from.kem) throw CryptoException.Format("eph equals static kem")
        val (tr, rr) = tokenRules(purpose)
        checkTokenRule(tr, token)
        checkTokenRule(rr, reconnectToken)
        Suite.validateOffer(suites)
        if (profile != null && !(purpose.isPairing || purpose == Purpose.CONNECTION)) throw CryptoException.Format("profile")
        if (rotations.isNotEmpty() && purpose != Purpose.RECONNECT) throw CryptoException.Format("rotations")
        if (rotations.size > HsLimits.MAX_ROTATIONS) throw CryptoException.Format("rotations")
        if (deviceAttest != null && purpose != Purpose.APP) throw CryptoException.Format("device_attest")
        if (apiKey != null && (purpose != Purpose.APP || apiKey.isEmpty() || apiKey.size > MAX_API_KEY)) {
            throw CryptoException.Format("api_key")
        }
        checkSasField(purpose, sasCommit, "sas_commit")
    }

    fun marshal(): ByteArray {
        validate()
        val b = JsonBuilder()
            .string("purpose", purpose.wire)
            .string("ctx", ctx)
            .raw("from", from.marshal())
            .base64("eph", eph.bytes())
        token?.let { b.string("token", it) }
        reconnectToken?.let { b.string("reconnect_token", it) }
        b.raw("suites", suites.joinToString(",", "[", "]"))
        profile?.let {
            val c = StrictJson.compactObject(it)
            if (c.size > HsLimits.MAX_OPAQUE_JSON) throw CryptoException.Format("profile")
            b.raw("profile", c)
        }
        if (rotations.isNotEmpty()) b.raw("rotations", marshalRotations(rotations))
        deviceAttest?.let { b.raw("device_attest", it.marshal()) }
        apiKey?.let { b.base64("api_key", it) }
        sasCommit?.let { b.base64("sas_commit", it) }
        return b.bytes()
    }

    companion object {
        /** An SPKI DER of a P-256 key is 91 bytes; anything far larger is not one. */
        private const val MAX_API_KEY = 512

        fun parse(body: ByteArray): HsInit {
            val o = StrictJson.parseObject(body)
            val purpose = Purpose.of(o.string("purpose"))
            val arr = o.array("suites")
            if (arr.isEmpty() || arr.size > Suite.MAX_OFFER) throw CryptoException.Format("suites")
            val profile = o.optObj("profile")?.let {
                val raw = it.rawBytes()
                if (raw.size > HsLimits.MAX_OPAQUE_JSON) throw CryptoException.Format("profile")
                StrictJson.compactObject(raw)
            }
            val rotations = parseRotations(o)
            if (rotations != null && purpose != Purpose.RECONNECT) throw CryptoException.Format("rotations")
            val init = HsInit(
                purpose = purpose,
                ctx = o.string("ctx"),
                from = Principal.parse(o.obj("from")),
                eph = KemPublicKey.parse(o.base64("eph", Suite.EK_SIZE)),
                token = optToken(o, "token"),
                reconnectToken = optToken(o, "reconnect_token"),
                suites = arr.map { it.asUint(0, 255).toInt() },
                profile = profile,
                rotations = rotations.orEmpty(),
                deviceAttest = o.optObj("device_attest")?.let { DeviceAttest.parse(it) },
                apiKey = if (o.has("api_key")) o.base64("api_key") else null,
                sasCommit = optSasField(o, "sas_commit"),
            )
            init.validate()
            return init
        }
    }
}

/** The hs.resp body (§6.2). */
class HsResp(
    val token: String?,
    val reconnectToken: String?,
    val suite: Int,
    val rotations: List<Rotation>,
    sig: ByteArray,
    /** n_R, exactly for purposes with a SAS (0.10.3). */
    sasNonce: ByteArray? = null,
) {
    private val sig = sig.copyOf()
    private val sasNonce = sasNonce?.copyOf()

    fun sig(): ByteArray = sig.copyOf()

    fun sasNonce(): ByteArray? = sasNonce?.copyOf()

    internal fun validate(p: Purpose) {
        val (tr, rr) = tokenRules(p)
        checkTokenRule(tr, token)
        checkTokenRule(rr, reconnectToken)
        if (suite !in 0..255 || sig.size != Suite.ED25519_SIGNATURE_SIZE) throw CryptoException.Format("hs.resp")
        if (rotations.isNotEmpty() && p != Purpose.RECONNECT) throw CryptoException.Format("rotations")
        if (rotations.size > HsLimits.MAX_ROTATIONS) throw CryptoException.Format("rotations")
        checkSasField(p, sasNonce, "sas_nonce")
    }

    fun marshal(p: Purpose): ByteArray {
        validate(p)
        val b = JsonBuilder()
        token?.let { b.string("token", it) }
        reconnectToken?.let { b.string("reconnect_token", it) }
        b.uint("suite", suite.toLong())
        if (rotations.isNotEmpty()) b.raw("rotations", marshalRotations(rotations))
        sasNonce?.let { b.base64("sas_nonce", it) }
        b.base64("sig", sig)
        return b.bytes()
    }

    companion object {
        fun parse(body: ByteArray, p: Purpose): HsResp {
            val o = StrictJson.parseObject(body)
            val rotations = parseRotations(o)
            if (rotations != null && p != Purpose.RECONNECT) throw CryptoException.Format("rotations")
            val r = HsResp(
                token = optToken(o, "token"),
                reconnectToken = optToken(o, "reconnect_token"),
                suite = o.uint("suite", 0, 255).toInt(),
                rotations = rotations.orEmpty(),
                sig = o.base64("sig", Suite.ED25519_SIGNATURE_SIZE),
                sasNonce = optSasField(o, "sas_nonce"),
            )
            r.validate(p)
            return r
        }
    }
}

/** The hs.fin body `{sig, sas_nonce?}` (§6.2): `sas_nonce` reveals n_I for purposes with a SAS (0.10.3). */
class HsFin(sig: ByteArray, sasNonce: ByteArray? = null) {
    private val sig = sig.copyOf()
    private val sasNonce = sasNonce?.copyOf()

    fun sig(): ByteArray = sig.copyOf()

    fun sasNonce(): ByteArray? = sasNonce?.copyOf()

    fun marshal(p: Purpose): ByteArray {
        if (sig.size != Suite.ED25519_SIGNATURE_SIZE) throw CryptoException.Format("hs.fin")
        checkSasField(p, sasNonce, "sas_nonce")
        val b = JsonBuilder().base64("sig", sig)
        sasNonce?.let { b.base64("sas_nonce", it) }
        return b.bytes()
    }

    companion object {
        fun parse(body: ByteArray, p: Purpose): HsFin {
            val o = StrictJson.parseObject(body)
            val f = HsFin(o.base64("sig", Suite.ED25519_SIGNATURE_SIZE), optSasField(o, "sas_nonce"))
            checkSasField(p, f.sasNonce, "sas_nonce")
            return f
        }
    }
}

/** hs.* inner types. */
object HsTypes {
    const val INIT = "hs.init"
    const val RESP = "hs.resp"
    const val FIN = "hs.fin"
}

/** Constant-time check that a relay key equals the one on record (§6.3). */
fun checkSender(collectSender: ByteArray, recordRelayKey: ByteArray) {
    if (!Ed25519.equalPublic(collectSender, recordRelayKey)) throw CryptoException.Protocol("relay sender")
}
