package com.vettid.core.vault

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * JSON for message bodies (§10.1: unknown members are ignored). Envelopes
 * and inner plaintexts stay with `:core:crypto`'s strict parser; bodies the
 * vault sends inside an authenticated session are decoded leniently into
 * the typed models of [VaultApi].
 */
object VaultJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun parseObject(
        b: ByteArray,
    ): JsonObject = if (b.isEmpty()) JsonObject(emptyMap()) else json.parseToJsonElement(String(b, Charsets.UTF_8)).jsonObject

    fun bytes(o: JsonObject): ByteArray = json.encodeToString(JsonObject.serializer(), o).toByteArray(Charsets.UTF_8)

    fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    fun long(o: JsonObject, k: String): Long? = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    fun <T> decode(d: DeserializationStrategy<T>, e: JsonElement): T = json.decodeFromJsonElement(d, e)
}
