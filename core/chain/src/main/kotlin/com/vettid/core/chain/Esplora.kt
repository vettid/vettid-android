package com.vettid.core.chain

import com.vettid.core.relay.fixedUserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** What [EsploraClient] asks about: an address, or a script hash (64 hex digits, as the server defines it). */
sealed interface ChainTarget {
    data class Address(val address: String) : ChainTarget {
        override fun toString(): String = "Address"
    }

    data class ScriptHash(val hex: String) : ChainTarget {
        override fun toString(): String = "ScriptHash"
    }
}

/** An address's (or script hash's) totals in satoshis: confirmed, and the change the unconfirmed transactions make. */
data class ChainSummary(val confirmedSats: Long, val unconfirmedSats: Long, val txCount: Long)

/** An unspent output. */
data class ChainUtxo(val txid: String, val vout: Int, val valueSats: Long, val confirmed: Boolean, val blockHeight: Long?)

/** A transaction as the server lists it. */
data class ChainTx(
    val txid: String,
    val confirmed: Boolean,
    val blockHeight: Long?,
    val blockTime: Long?,
    val feeSats: Long?,
    val inputs: List<ChainTxInput>,
    val outputs: List<ChainTxOutput>,
)

/** An input: the output it spends, and that output's address and value when the server gives them (none for a coinbase). */
data class ChainTxInput(val prevTxid: String?, val prevVout: Int?, val prevAddress: String?, val prevValueSats: Long?)

data class ChainTxOutput(val scriptHex: String, val address: String?, val valueSats: Long)

/** Why a broadcast was refused, from the server's (Bitcoin Core's) words. */
enum class BroadcastRefusal {
    /** The transaction is already known: in the mempool or in a block. */
    ALREADY_KNOWN,

    /** An input is spent or does not exist. */
    INPUTS_MISSING_OR_SPENT,

    /** Under the minimum relay fee or the mempool's minimum fee. */
    FEE_TOO_LOW,

    /** It conflicts with a transaction in the mempool. */
    CONFLICT,

    /** An output is too small to relay. */
    DUST,

    /** A signature or script check failed. */
    SCRIPT_FAILED,

    /** The server could not decode the transaction. */
    MALFORMED,

    /** Refused for another reason. */
    OTHER,
}

/**
 * A chain server call that gave no usable answer. Its message is its kind only, with no cause attached: an address, a txid or the
 * server's path never reaches a log or a crash report through it.
 */
sealed class ChainError(kind: String) : Exception(kind) {
    /** No connection, a TLS failure, or the connection closed. */
    class Unreachable : ChainError("unreachable")

    /** Longer than the call's time limit. */
    class Timeout : ChainError("timeout")

    /** A redirect: refused, never followed. */
    class Redirected : ChainError("redirected")

    /** A body over the call's size limit, or shorter than it announced. */
    class TooLarge : ChainError("too large")

    /** 404: not on this server (a transaction it does not know). */
    class NotFound : ChainError("not found")

    /** Any other status. */
    class BadStatus(val code: Int) : ChainError("status $code")

    /** An answer that is not what Esplora sends. */
    class Malformed : ChainError("malformed")

    /** More transactions than the client reads for one address. */
    class TooMany : ChainError("too many transactions")

    /**
     * The server refused the broadcast. [serverText] is its own words (capped), for the screen to choose a text by [reason];
     * never logged.
     */
    class BroadcastRefused(val reason: BroadcastRefusal, val serverText: String) : ChainError("broadcast refused: $reason")
}

/**
 * The app's Esplora client (ANDROID-PLAN §4 "The chain source"): the tip height, block hashes, an address's (or script hash's)
 * totals, transactions (all pages) and unspent outputs, a transaction's raw bytes, the fee estimates, and the broadcast.
 *
 * Every request carries the app's fixed `User-Agent: VettID-Android` and nothing else of the phone's: no cookies, no cache, no
 * credentials, no identifier. Redirects are refused (a [ChainError.Redirected]). Each call has a time limit and a size limit. Nothing
 * here logs.
 */
class EsploraClient internal constructor(
    private val base: HttpUrl,
    http: OkHttpClient,
    private val limits: Limits = Limits(),
) {
    constructor(server: ChainServerUrl, http: OkHttpClient) : this(server.url, http)

    /** Time and size limits; tests make them small. */
    internal data class Limits(
        val callTimeoutMs: Long = CALL_TIMEOUT_MS,
        val smallBytes: Long = SMALL_BYTES,
        val listBytes: Long = LIST_BYTES,
        val rawTxBytes: Long = RAW_TX_BYTES,
        val maxTransactions: Int = MAX_TRANSACTIONS,
    )

    private val client: OkHttpClient = http.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(limits.callTimeoutMs, TimeUnit.MILLISECONDS)
        .fixedUserAgent()
        .build()

    /** The height of the server's best block. */
    suspend fun tipHeight(): Long = text(listOf("blocks", "tip", "height"), limits.smallBytes).toLongOrNull()?.takeIf { it >= 0 }
        ?: throw ChainError.Malformed()

    /** The hash of the block at [height], in display order. */
    suspend fun blockHash(height: Long): String = text(listOf("block-height", height.toString()), limits.smallBytes).also {
        if (!isHash(it)) throw ChainError.Malformed()
    }

    /** [target]'s confirmed and unconfirmed totals. */
    suspend fun summary(target: ChainTarget): ChainSummary {
        val o = json(targetPath(target), limits.smallBytes).asObject()
        val chain = o["chain_stats"].asObject()
        val mempool = o["mempool_stats"].asObject()
        fun balance(s: JsonObject) = s.sats("funded_txo_sum") - s.sats("spent_txo_sum")
        return ChainSummary(
            confirmedSats = balance(chain),
            unconfirmedSats = balance(mempool),
            txCount = chain.count("tx_count") + mempool.count("tx_count"),
        )
    }

    /**
     * Every transaction of [target], newest first: the unconfirmed ones and the first confirmed page, then the next confirmed
     * pages (`…/txs/chain/<last txid>`) until one is short. More than [Limits.maxTransactions] is a [ChainError.TooMany].
     */
    suspend fun transactions(target: ChainTarget): List<ChainTx> {
        val all = ArrayList<ChainTx>()
        val seen = HashSet<String>()
        var path = targetPath(target) + "txs"
        while (true) {
            val page = json(path, limits.listBytes).asArray().map { parseTx(it) }
            for (tx in page) {
                // A page that repeats a transaction would page forever.
                if (!seen.add(tx.txid)) throw ChainError.Malformed()
                all += tx
            }
            if (all.size > limits.maxTransactions) throw ChainError.TooMany()
            val confirmed = page.filter { it.confirmed }
            if (confirmed.size < CONFIRMED_PAGE) return all
            path = targetPath(target) + listOf("txs", "chain", confirmed.last().txid)
        }
    }

    /** [target]'s unspent outputs, confirmed or not. */
    suspend fun unspent(target: ChainTarget): List<ChainUtxo> = json(targetPath(target) + "utxo", limits.listBytes).asArray().map {
        val o = it.asObject()
        val status = o["status"].asObject()
        ChainUtxo(
            txid = o.hash("txid"),
            vout = o.index("vout"),
            valueSats = o.sats("value"),
            confirmed = status.flag("confirmed"),
            blockHeight = status.optCount("block_height"),
        )
    }

    /** The transaction's raw bytes (`GET /tx/<txid>/hex`); a [ChainError.NotFound] if the server does not know it. */
    suspend fun rawTransaction(txid: String): ByteArray {
        require(isHash(txid)) { "not a txid" }
        val hex = text(listOf("tx", txid, "hex"), limits.rawTxBytes)
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it in HEX }) throw ChainError.Malformed()
        return hex.hexToBytes()
    }

    /** Fee estimates in sat/vB by confirmation target in blocks (`/fee-estimates`). */
    suspend fun feeEstimates(): Map<Int, Double> = json(listOf("fee-estimates"), limits.smallBytes).asObject().entries.associate { (k, v) ->
        val target = k.toIntOrNull()?.takeIf { it in 1..MAX_TARGET } ?: throw ChainError.Malformed()
        val rate = (v as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
            ?: throw ChainError.Malformed()
        target to rate
    }

    /** Broadcasts [tx] (`POST /tx`, the hex as the body); returns the txid the server answers. */
    suspend fun broadcast(tx: ByteArray): String {
        require(tx.isNotEmpty()) { "empty transaction" }
        val body = tx.toHex().toRequestBody(TEXT)
        val request = Request.Builder().url(url(listOf("tx"))).post(body).header("Accept", "text/plain").build()
        val (code, bytes) = call(request, limits.smallBytes)
        val text = bytes.decodeToString().trim()
        return when (code) {
            HTTP_OK -> text.lowercase().takeIf(::isHash) ?: throw ChainError.Malformed()
            HTTP_BAD_REQUEST -> throw ChainError.BroadcastRefused(refusal(text), text.take(MAX_SERVER_TEXT))
            else -> throw status(code)
        }
    }

    private suspend fun text(path: List<String>, max: Long): String {
        val (code, bytes) = call(Request.Builder().url(url(path)).get().header("Accept", "text/plain").build(), max)
        if (code != HTTP_OK) throw status(code)
        return bytes.decodeToString().trim()
    }

    private suspend fun json(path: List<String>, max: Long): JsonElement {
        val (code, bytes) = call(Request.Builder().url(url(path)).get().header("Accept", "application/json").build(), max)
        if (code != HTTP_OK) throw status(code)
        return try {
            Json.parseToJsonElement(bytes.decodeToString())
        } catch (_: IllegalArgumentException) {
            throw ChainError.Malformed()
        }
    }

    private fun url(path: List<String>): HttpUrl = base.newBuilder().apply { path.forEach { addPathSegment(it) } }.build()

    private fun targetPath(target: ChainTarget): List<String> = when (target) {
        is ChainTarget.Address -> {
            val a = target.address
            require(a.isNotEmpty() && a.length <= MAX_ADDRESS && a.all { it.isLetterOrDigit() && it.code < ASCII }) { "not an address" }
            listOf("address", target.address)
        }
        is ChainTarget.ScriptHash -> {
            require(isHash(target.hex)) { "not a script hash" }
            listOf("scripthash", target.hex)
        }
    }

    /** One request: its status and its whole body, at most [max] bytes; redirects, timeouts and transport failures as [ChainError]. */
    @Suppress("ThrowsCount")
    private suspend fun call(request: Request, max: Long): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { r ->
                if (r.code in REDIRECTS) throw ChainError.Redirected()
                val body = r.body
                val declared = body.contentLength()
                if (declared > max) throw ChainError.TooLarge()
                val buf = okio.Buffer()
                body.source().use { s ->
                    while (buf.size <= max) {
                        if (s.read(buf, READ_CHUNK) < 0) break
                    }
                }
                // Over the limit, or shorter than the body announced.
                if (buf.size > max || (declared >= 0 && buf.size != declared)) throw ChainError.TooLarge()
                r.code to buf.readByteArray()
            }
        } catch (e: ChainError) {
            throw e
        } catch (_: InterruptedIOException) {
            throw ChainError.Timeout()
        } catch (_: IOException) {
            throw ChainError.Unreachable()
        }
    }

    private fun status(code: Int): ChainError = if (code == HTTP_NOT_FOUND) ChainError.NotFound() else ChainError.BadStatus(code)

    private fun parseTx(e: JsonElement): ChainTx {
        val o = e.asObject()
        val status = o["status"].asObject()
        val confirmed = status.flag("confirmed")
        return ChainTx(
            txid = o.hash("txid"),
            confirmed = confirmed,
            blockHeight = status.optCount("block_height"),
            blockTime = status.optCount("block_time"),
            feeSats = o["fee"]?.takeUnless { it is JsonNull }?.let { o.sats("fee") },
            inputs = o["vin"].asArray().map { v ->
                val i = v.asObject()
                val coinbase = (i["is_coinbase"] as? JsonPrimitive)?.booleanOrNull == true
                val prev = i["prevout"]?.takeUnless { it is JsonNull }?.asObject()
                ChainTxInput(
                    prevTxid = if (coinbase) null else i.hash("txid"),
                    prevVout = if (coinbase) null else i.index("vout"),
                    prevAddress = prev?.optString("scriptpubkey_address"),
                    prevValueSats = prev?.sats("value"),
                )
            },
            outputs = o["vout"].asArray().map { v ->
                val out = v.asObject()
                val script = out.optString("scriptpubkey") ?: throw ChainError.Malformed()
                if (script.length % 2 != 0 || !script.all { it in HEX }) throw ChainError.Malformed()
                ChainTxOutput(scriptHex = script, address = out.optString("scriptpubkey_address"), valueSats = out.sats("value"))
            },
        )
    }

    internal companion object {
        const val CALL_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_S = 15L
        const val READ_TIMEOUT_S = 20L

        /** Heights, hashes, totals, fee estimates, a broadcast's answer. */
        const val SMALL_BYTES = 64L * 1024

        /** A page of transactions (up to 50 unconfirmed and 25 confirmed), or an address's unspent outputs. */
        const val LIST_BYTES = 8L * 1024 * 1024

        /** A transaction's hex: twice the largest transaction a block can hold (4,000,000 bytes). */
        const val RAW_TX_BYTES = 8_000_000L + 2

        const val MAX_TRANSACTIONS = 10_000

        /** Esplora's confirmed page: a shorter one is the last. */
        const val CONFIRMED_PAGE = 25

        const val MAX_SERVER_TEXT = 500
        const val MAX_ADDRESS = 128
        const val MAX_TARGET = 1008
        const val MAX_SATS = 2_100_000_000_000_000L
        const val READ_CHUNK = 64L * 1024
        const val ASCII = 128
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        val REDIRECTS = 300..399
        val TEXT = "text/plain".toMediaType()
        const val HEX = "0123456789abcdef"

        fun isHash(s: String): Boolean = s.length == 64 && s.all { it in HEX }

        /** Bitcoin Core's reject reasons as Esplora passes them on, by kind; the first kind with a match wins. */
        private val REFUSALS: List<Pair<BroadcastRefusal, List<String>>> = listOf(
            BroadcastRefusal.ALREADY_KNOWN to
                listOf("already in block chain", "txn-already-known", "txn-already-in-mempool", "already have transaction"),
            BroadcastRefusal.INPUTS_MISSING_OR_SPENT to listOf("missingorspent", "missing-inputs", "missing inputs"),
            BroadcastRefusal.FEE_TOO_LOW to
                listOf("min relay fee not met", "mempool min fee not met", "insufficient fee", "min-fee-not-met"),
            BroadcastRefusal.CONFLICT to listOf("txn-mempool-conflict"),
            BroadcastRefusal.DUST to listOf("dust"),
            BroadcastRefusal.SCRIPT_FAILED to listOf("script-verify-flag", "bad-witness"),
            BroadcastRefusal.MALFORMED to listOf("decode failed", "tx decode"),
        )

        /** The kind of a refused broadcast, from the server's words. */
        fun refusal(text: String): BroadcastRefusal {
            val t = text.lowercase()
            return REFUSALS.firstOrNull { (_, phrases) -> phrases.any { it in t } }?.first ?: BroadcastRefusal.OTHER
        }

        private fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: throw ChainError.Malformed()

        private fun JsonElement?.asArray(): JsonArray = this as? JsonArray ?: throw ChainError.Malformed()

        private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

        fun JsonObject.sats(key: String): Long = number(key)?.takeIf { it in 0..MAX_SATS } ?: throw ChainError.Malformed()

        fun JsonObject.count(key: String): Long = number(key)?.takeIf { it >= 0 } ?: throw ChainError.Malformed()

        fun JsonObject.optCount(key: String): Long? = if (this[key] == null || this[key] is JsonNull) null else count(key)

        fun JsonObject.index(key: String): Int = number(key)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: throw ChainError.Malformed()

        fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw ChainError.Malformed()

        fun JsonObject.hash(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(::isHash)
            ?: throw ChainError.Malformed()

        fun JsonObject.optString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
