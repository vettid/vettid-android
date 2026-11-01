package com.vettid.core.data.votes

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.envelope.Padding
import com.vettid.core.crypto.envelope.Ulid
import java.time.Instant
import com.vettid.core.crypto.json.StrictJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The app's work with the public votes site for the vault (VAULT-MESSAGING 0.81.3 §10.19.8 `vote.site.sync`; ANDROID-PLAN
 * 0.1.107): what it fetches, and the requests it makes of it within the per-call limits. The vault decides everything; the
 * app hands over public documents, with no time of its own.
 */
@Suppress("TooManyFunctions")
object VoteSync {
    /** §5.5: a padded plaintext over this travels as a claim-check blob to the app's own vault; at or under, inline. */
    const val INLINE_MAX = 245_760

    /** The encoded request body, at most (`attachment_max_bytes`, §10.5.1). Never sent over. */
    const val BODY_MAX = 8_257_532

    const val MAX_LISTS = 8

    /**
     * Whether a request body travels inline: §5.5 caps the padded **inner plaintext**, which is the body inside the inner
     * envelope (`v`, `id`, `type`, `ts`, `seq`…) and padded to its bucket, not the body alone. Measured through the real
     * encoder with the longest `seq` and ids; over the cap it goes as a claim-check blob.
     */
    fun fitsInline(body: ByteArray): Boolean = inline(body) == Inline.FITS

    /** Whether a body goes inline, is over the padded limit (a size failure), or cannot be put in an inner message at all. */
    enum class Inline { FITS, TOO_LARGE, OTHER }

    fun inline(body: ByteArray): Inline {
        val marshalled = try {
            Inner(Ulid.new(), TYPE, Instant.now(), seq = StrictJson.MAX_SAFE_INTEGER, body = body).marshal(Mode.SESSION)
        } catch (_: CryptoException) {
            return Inline.OTHER
        }
        return try {
            Padding.paddedLen(marshalled.size)
            Inline.FITS
        } catch (_: CryptoException) {
            Inline.TOO_LARGE
        }
    }

    /** The vault's `ValidChoiceID` (vettid-vault `vms/votewire`): 1 to 32 bytes of `[a-z0-9-]`. */
    fun validChoiceId(id: String): Boolean = id.length in 1..MAX_CHOICE_ID && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    private const val MAX_CHOICE_ID = 32

    private const val TYPE = "vote.site.sync"
    const val MAX_ENTRIES = 8

    /** One list over this size goes in a call of its own. */
    const val BIG_LIST = 1_048_576

    /** New (not held) proposals the app reads the documents of in one run. */
    const val MAX_NEW = 24

    /** The most extensions the site holds for a proposal (VOTES-SITE §4), and the most the vault takes in one entry. */
    const val MAX_EXTENSIONS = 24

    // The vault's shape limits (VAULT-MESSAGING 0.81.5 §10.19.8 "Shape checks before sending"; vettid-vault `features/vote/sync.go`,
    // `api.go`, `vms/votewire/votewire.go` at bc5660f): one item that breaks one makes the vault refuse the whole call, so the app
    // checks each item first and leaves out, or replaces, what it would refuse. Content, signatures, hashes: the vault decides.

    /** A detached signature: exactly 64 raw bytes. */
    const val SIG_BYTES = 64

    /** `proposal.json`, decoded. */
    const val PROPOSAL_MAX = 65_536

    /** A sigstore bundle, encoded. */
    const val BUNDLE_MAX = 65_536

    /** `extension-<n>.json`, decoded. */
    const val EXTENSION_MAX = 4_096

    /** A list the vault takes: a document and a 64-byte signature. */
    fun sendable(l: ServedList): Boolean = l.doc.isNotEmpty() && l.sig.size == SIG_BYTES

    /**
     * A document as the vault takes it (0.81.5): a `proposal` over [PROPOSAL_MAX] is `unreachable`; a `bundle` that is over
     * [BUNDLE_MAX] is left out; the extensions end at the [MAX_EXTENSIONS]th, or before the first whose document is over
     * [EXTENSION_MAX], whose signature is not [SIG_BYTES], or whose bundle is missing or over [BUNDLE_MAX]. `proposal_sig` goes as
     * served (the vault judges it).
     */
    fun shaped(d: Document): Document = when {
        d !is Document.Found -> d
        d.proposal.size > PROPOSAL_MAX -> Document.Unreachable
        else -> Document.Found(
            d.proposal,
            d.proposalSig,
            d.bundle?.takeIf(::fitsBundle),
            d.extensions.take(MAX_EXTENSIONS).takeWhile { x ->
                x.extension.size <= EXTENSION_MAX && x.sig?.size == SIG_BYTES && x.bundle != null && fitsBundle(x.bundle)
            },
        )
    }

    private fun fitsBundle(b: JsonObject): Boolean = encoded(b).size <= BUNDLE_MAX

    /** An `index` element as the vault takes it: one whose `service_id` is present but not a string is left out. */
    fun shapedIndex(index: JsonObject?): JsonObject? {
        val serviceId = index?.get("service_id") ?: return index
        return index.takeIf { serviceId is JsonPrimitive && serviceId.isString }
    }

    /** Whether `results` and `results_sig` are what the vault takes: results not empty and a 64-byte signature. */
    fun submittable(results: ByteArray, resultsSig: ByteArray): Boolean = results.isNotEmpty() && resultsSig.size == SIG_BYTES

    /** A service list as the site serves it: the exact bytes, its detached signature (64 raw bytes) and its version. */
    class ServedList(val doc: ByteArray, val sig: ByteArray, val version: Long)

    /** `services.json`'s `version`; null when the document does not read (the vault judges it, so it is still sent). */
    fun versionOf(doc: ByteArray): Long? = try {
        StrictJson.parseObject(doc).uint("version", 0, StrictJson.MAX_SAFE_INTEGER)
    } catch (_: CryptoException) {
        null
    }

    /** The services part of a run: what to send, given the list the vault holds ([heldVersion], [heldSha256]). */
    sealed interface ListPlan {
        data object Nothing : ListPlan

        data class Confirm(val version: Long, val sha256: String) : ListPlan

        /** The versions to hand over, oldest first. */
        data class Send(val lists: List<ServedList>) : ListPlan
    }

    /**
     * Chooses what to do with the served list [current] (the vault holds [heldVersion] with [heldSha256], or nothing):
     * the same bytes are only confirmed (`services_confirm`), a newer list goes after the versions between, oldest first
     * ([between] are the versions in order after the held one, those the site already serves), an older one is not sent.
     */
    @Suppress("ReturnCount")
    fun planLists(current: ServedList, heldVersion: Long?, heldSha256: String?, between: List<ServedList>): ListPlan {
        if (heldVersion == null) return ListPlan.Send(listOf(current)) // a vault with no list takes any that verifies
        if (heldSha256 != null && Base64s.encodeStd(Bytes.sha256(current.doc)) == heldSha256) {
            return ListPlan.Confirm(current.version, heldSha256)
        }
        return when {
            current.version < heldVersion -> ListPlan.Nothing
            current.version == heldVersion -> ListPlan.Send(listOf(current))
            // Each version chains to the one before: all that are missing, in order, then the current one.
            between.size == (current.version - heldVersion - 1).toInt() -> ListPlan.Send(between + current)
            // A copy the site does not serve yet (404): the contiguous ones go, the rest at the next run.
            else -> if (between.isEmpty()) ListPlan.Nothing else ListPlan.Send(between)
        }
    }

    /** `{doc, sig}` of a list. */
    fun listJson(l: ServedList): JsonObject = buildJsonObject {
        put("doc", Base64s.encodeStd(l.doc))
        put("sig", Base64s.encodeStd(l.sig))
    }

    /**
     * The calls that carry [lists] (oldest first): at most [MAX_LISTS], one alone when it is over [BIG_LIST] (and none
     * carries a list that alone would pass [BODY_MAX]). The first call also carries [confirm] when there is one.
     */
    fun listCalls(lists: List<ServedList>): List<JsonObject> {
        val calls = ArrayList<List<ServedList>>()
        var current = ArrayList<ServedList>()
        for (l in lists) {
            // Never sent: the vault would refuse it (and the call with it); a later version cannot chain without it, so the catch-up
            // stops here and the later versions wait for the next run.
            if (l.doc.size > BODY_MAX || !sendable(l)) break
            val big = l.doc.size > BIG_LIST
            val full = current.size >= MAX_LISTS || current.any { it.doc.size > BIG_LIST }
            if (current.isNotEmpty() && (big || full)) {
                calls += current
                current = ArrayList()
            }
            current += l
        }
        if (current.isNotEmpty()) calls += current
        return calls.map { call -> buildJsonObject { put("services", JsonArray(call.map(::listJson))) } }
    }

    fun confirmCall(version: Long, sha256: String): JsonObject = buildJsonObject {
        put("services_confirm", buildJsonObject { put("version", version); put("sha256", sha256) })
    }

    /** One proposal's documents as read from the site. */
    sealed interface Document {
        class Found(
            val proposal: ByteArray,
            val proposalSig: ByteArray?,
            val bundle: JsonObject?,
            val extensions: List<Extension>,
        ) : Document

        data object Absent : Document

        data object Unreachable : Document
    }

    class Extension(val extension: ByteArray, val sig: ByteArray?, val bundle: JsonObject?)

    /** `{proposal_id, index?, document}`, each in the shape the vault takes ([shapedIndex], [shaped]); no time. */
    fun entryJson(proposalId: String, served: JsonObject?, document: Document): JsonObject = buildJsonObject {
        val index = shapedIndex(served)
        val d = shaped(document)
        put("proposal_id", proposalId)
        if (index != null) put("index", index)
        put(
            "document",
            when (d) {
                Document.Absent -> buildJsonObject { put("status", "absent") }
                Document.Unreachable -> buildJsonObject { put("status", "unreachable") }
                is Document.Found -> buildJsonObject {
                    put("status", "found")
                    put("proposal", Base64s.encodeStd(d.proposal))
                    d.proposalSig?.let { put("proposal_sig", Base64s.encodeStd(it)) }
                    d.bundle?.let { put("bundle", it) }
                    put(
                        "extensions",
                        buildJsonArray {
                            for (x in d.extensions) {
                                add(
                                    buildJsonObject {
                                        put("extension", Base64s.encodeStd(x.extension))
                                        x.sig?.let { put("extension_sig", Base64s.encodeStd(it)) }
                                        x.bundle?.let { put("bundle", it) }
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }

    /**
     * The calls that carry [entries]: at most [MAX_ENTRIES] each and each body within [BODY_MAX]. An entry that alone
     * would pass the limit is not sent (the vault would refuse the call), and the others are.
     */
    fun entryCalls(entries: List<JsonObject>): List<JsonObject> {
        val calls = ArrayList<JsonObject>()
        var batch = ArrayList<JsonObject>()
        var size = ENVELOPE
        fun flush() {
            if (batch.isNotEmpty()) calls += buildJsonObject { put("entries", JsonArray(batch)) }
            batch = ArrayList()
            size = ENVELOPE
        }
        for (e in entries) {
            val n = encoded(e).size + 1
            if (n + ENVELOPE > BODY_MAX) continue
            if (batch.size >= MAX_ENTRIES || size + n > BODY_MAX) flush()
            batch += e
            size += n
        }
        flush()
        return calls
    }

    private const val ENVELOPE = 16 // {"entries":[]}

    /** The request body as sent. */
    fun encoded(body: JsonElement): ByteArray = body.toString().toByteArray(Charsets.UTF_8)

    // (0.81.6) The vault parses the whole request strictly (StrictJson: valid UTF-8, no duplicate member names at any depth, nesting
    // at most 32): an inline request as the whole inner message (vms/envelope/inner.go), whose `body` is level 2. The site's own JSON
    // is copied into it, so each such value is parsed as strictly first, with the depth left where it sits (the same budgets serve a
    // claim-check blob, whose body is level 1: one level spare).

    /** An `index` element: inner > body > `entries` > entry > `index`. */
    const val INDEX_DEPTH = StrictJson.MAX_DEPTH - 4

    /** A proposal's `bundle`: inner > body > `entries` > entry > `document` > `bundle`. */
    const val BUNDLE_DEPTH = StrictJson.MAX_DEPTH - 5

    /** An extension's `bundle`: … `document` > `extensions` > extension > `bundle`. */
    const val EXTENSION_BUNDLE_DEPTH = StrictJson.MAX_DEPTH - 7

    /** The results' `bundle` in `vote.results.submit`: inner > body > `bundle`. */
    const val RESULTS_BUNDLE_DEPTH = StrictJson.MAX_DEPTH - 2

    /** An `index` element larger than this is left out before it is parsed (none comes near it; it bounds the parse's memory). */
    const val INDEX_ELEMENT_MAX = 65_536

    /**
     * A site JSON document that is an object, as one, if it passes the vault's strict rules within [maxDepth]; null when it does
     * not ("not an object", 0.81.6: a bundle is left out, the extensions end there).
     */
    fun jsonObject(bytes: ByteArray, maxDepth: Int = StrictJson.MAX_DEPTH): JsonObject? = try {
        StrictJson.parseObject(bytes, maxDepth)
        val o = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        // What is sent is the re-encoded value, so that is what must pass: re-encoding can merge names the strict parse kept apart
        // (a lone surrogate, "\ud800", and "?" both come out "?").
        o?.also { StrictJson.parseObject(encoded(it), maxDepth) }
    } catch (_: CryptoException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * The index's elements (`index.json` is an array), each judged on its own bytes: one that is not a strict object within
     * [INDEX_DEPTH], or whose `proposal_id` is not a valid proposal id (a canonical ULID), is left out (0.81.6: not sent, and no
     * entry for it). An index that is not an array yields none.
     */
    fun indexOf(bytes: ByteArray): List<JsonObject> = elements(bytes).filter { it.size <= INDEX_ELEMENT_MAX }
        .mapNotNull { jsonObject(it, INDEX_DEPTH) }
        .filter { e -> proposalIdOf(e)?.let(Ulid::isValid) == true }

    /**
     * The bytes of each element of a top-level JSON array, found by its structure only (strings and escapes skipped, brackets
     * counted), so that one bad element does not hide the others; empty when [bytes] is not a well-formed array at that level (a
     * stray `}` or `]`, an empty element, an unterminated string or array). Every step moves forward: it ends on any input.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LoopWithTooManyJumpStatements")
    internal fun elements(bytes: ByteArray): List<ByteArray> {
        var i = 0
        fun ws() {
            while (i < bytes.size && bytes[i].toInt().toChar() in " \t\r\n") i++
        }
        ws()
        if (i >= bytes.size || bytes[i] != '['.code.toByte()) return emptyList()
        i++
        ws()
        if (i < bytes.size && bytes[i] == ']'.code.toByte()) return emptyList()
        val out = ArrayList<ByteArray>()
        while (true) {
            ws()
            val start = i
            var depth = 0
            var inString = false
            // To the end of this element: a `,` or `]` at depth 0.
            while (i < bytes.size) {
                val c = bytes[i].toInt().toChar()
                if (inString) {
                    if (c == '\\') i++ else if (c == '"') inString = false
                } else if (c == '"') {
                    inString = true
                } else if (c == '{' || c == '[') {
                    depth++
                } else if (c == '}' || c == ']') {
                    if (depth == 0) {
                        if (c == '}') return emptyList() // a `}` that closes nothing
                        break
                    }
                    depth--
                } else if (c == ',' && depth == 0) {
                    break
                }
                i++
            }
            if (i >= bytes.size || inString) return emptyList()
            var end = i
            while (end > start && bytes[end - 1].toInt().toChar() in " \t\r\n") end--
            if (end == start) return emptyList() // an empty element (`[,]`, `[1,]`)
            out += bytes.copyOfRange(start, end)
            if (bytes[i] == ']'.code.toByte()) return out
            i++ // past the `,`
        }
    }

    /** An index element's `proposal_id`. */
    fun proposalIdOf(index: JsonObject): String? = (index["proposal_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** An index element's `extended` count when it reads. */
    fun extendedOf(index: JsonObject?): Int? =
        (index?.get("extended") as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.content?.toIntOrNull()
}
