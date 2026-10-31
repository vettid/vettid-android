package com.vettid.core.data.votes

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
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
    const val MAX_ENTRIES = 8

    /** One list over this size goes in a call of its own. */
    const val BIG_LIST = 1_048_576

    /** New (not held) proposals the app reads the documents of in one run. */
    const val MAX_NEW = 24

    /** The most extensions the site holds for a proposal (VOTES-SITE §4). */
    const val MAX_EXTENSIONS = 24

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
            if (l.doc.size > BODY_MAX) break // never sent: the vault would refuse it; a later list cannot chain without it
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

    /** `{proposal_id, index?, document}`; no time (the vault stamps an `absent` itself). */
    fun entryJson(proposalId: String, index: JsonObject?, d: Document): JsonObject = buildJsonObject {
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

    /** A site JSON document that is an object, as one; null when it is not (the vault judges what it is sent). */
    fun jsonObject(bytes: ByteArray): JsonObject? = try {
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The index's elements (`index.json` is an array); an unreadable index yields none. */
    fun indexOf(bytes: ByteArray): List<JsonObject> = try {
        (Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    /** An index element's `proposal_id`. */
    fun proposalIdOf(index: JsonObject): String? = (index["proposal_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** An index element's `extended` count when it reads. */
    fun extendedOf(index: JsonObject?): Int? =
        (index?.get("extended") as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.content?.toIntOrNull()
}
