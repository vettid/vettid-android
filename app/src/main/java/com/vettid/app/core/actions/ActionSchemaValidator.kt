package com.vettid.app.core.actions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Tiny JSON-Schema validator covering the subset Phase-1 actions need.
 * Mirrors the vault-side validator (action_schema.go). Both layers
 * validate; the vault is the trust boundary, the app is for UX.
 *
 * Supports: object (required, properties, additionalProperties:false),
 * string (minLength, maxLength), integer (minimum, maximum), number,
 * boolean, array (items, minItems, maxItems), enum.
 *
 * Returns null on success or a human-readable error string on failure
 * (suitable for inline form messages).
 */
object ActionSchemaValidator {
    fun validate(schemaJson: String, value: JsonElement): String? {
        val schema = try {
            JsonParser.parseString(schemaJson).asJsonObject
        } catch (e: Exception) {
            return "schema parse: ${e.message}"
        }
        return walk(schema, value, "")
    }

    private fun walk(schema: JsonObject, value: JsonElement, path: String): String? {
        // enum check
        schema.get("enum")?.takeIf { it.isJsonArray }?.let { enumArr ->
            val list = enumArr.asJsonArray
            val matched = list.any { jsonEquals(it, value) }
            if (!matched) return "$path: not in enum"
        }

        val type = schema.get("type")?.asString ?: return null

        when (type) {
            "object" -> {
                if (!value.isJsonObject) return "$path: expected object"
                val obj = value.asJsonObject
                schema.get("required")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { r ->
                    val rs = r.asString
                    if (!obj.has(rs)) return "$path: missing required \"$rs\""
                }
                val props = schema.getAsJsonObject("properties")
                val additional = schema.get("additionalProperties")?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) it.asBoolean else true } ?: true
                obj.entrySet().forEach { (k, v) ->
                    val ps = props?.getAsJsonObject(k)
                    if (ps == null) {
                        if (!additional) return "$path: unexpected property \"$k\""
                        return@forEach
                    }
                    val err = walk(ps, v, "$path.$k")
                    if (err != null) return err
                }
            }
            "string" -> {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return "$path: expected string"
                val s = value.asString
                schema.get("minLength")?.asInt?.let { if (s.length < it) return "$path: shorter than minLength" }
                schema.get("maxLength")?.asInt?.let { if (s.length > it) return "$path: longer than maxLength" }
            }
            "integer" -> {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return "$path: expected integer"
                val d = value.asDouble
                if (d != d.toLong().toDouble()) return "$path: not an integer"
                schema.get("minimum")?.asDouble?.let { if (d < it) return "$path: below minimum" }
                schema.get("maximum")?.asDouble?.let { if (d > it) return "$path: above maximum" }
            }
            "number" -> {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return "$path: expected number"
            }
            "boolean" -> {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) return "$path: expected boolean"
            }
            "array" -> {
                if (!value.isJsonArray) return "$path: expected array"
                val arr = value.asJsonArray
                schema.get("minItems")?.asInt?.let { if (arr.size() < it) return "$path: fewer than minItems" }
                schema.get("maxItems")?.asInt?.let { if (arr.size() > it) return "$path: more than maxItems" }
                schema.getAsJsonObject("items")?.let { itemsSchema ->
                    arr.forEachIndexed { i, item ->
                        val err = walk(itemsSchema, item, "$path[$i]")
                        if (err != null) return err
                    }
                }
            }
        }
        return null
    }

    private fun jsonEquals(a: JsonElement, b: JsonElement): Boolean {
        if (a.isJsonPrimitive && b.isJsonPrimitive) {
            val pa = a.asJsonPrimitive; val pb = b.asJsonPrimitive
            return when {
                pa.isString && pb.isString -> pa.asString == pb.asString
                pa.isNumber && pb.isNumber -> pa.asDouble == pb.asDouble
                pa.isBoolean && pb.isBoolean -> pa.asBoolean == pb.asBoolean
                else -> false
            }
        }
        return a == b
    }
}
