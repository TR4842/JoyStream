package com.joystream.app.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * Small, forgiving JSON accessors. Provider payloads drift over time: a field
 * can arrive as a string, a number or be missing entirely, so every accessor
 * here degrades to null instead of throwing.
 */

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

fun JsonElement?.stringOrNull(): String? {
    val element = this ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
}

fun JsonElement?.intOrNullValue(): Int? = this?.stringOrNull()?.toDoubleOrNull()?.toInt()
    ?: (this as? JsonPrimitive)?.doubleOrNull?.toInt()

fun JsonElement?.longOrNullValue(): Long? = (this as? JsonPrimitive)?.longOrNull
    ?: this?.stringOrNull()?.toDoubleOrNull()?.toLong()

fun JsonElement?.doubleOrNullValue(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    ?: this?.stringOrNull()?.toDoubleOrNull()

fun JsonElement?.boolOrNullValue(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    return when (primitive.contentOrNull?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

/** First present, non-blank string among [keys]. */
fun JsonObject?.str(vararg keys: String): String? {
    val obj = this ?: return null
    for (key in keys) {
        obj[key].stringOrNull()?.let { return it }
    }
    return null
}

fun JsonObject?.obj(vararg keys: String): JsonObject? {
    val obj = this ?: return null
    for (key in keys) {
        (obj[key] as? JsonObject)?.let { return it }
    }
    return null
}

fun JsonObject?.arr(vararg keys: String): JsonArray? {
    val obj = this ?: return null
    for (key in keys) {
        (obj[key] as? JsonArray)?.let { return it }
    }
    return null
}

fun JsonObject?.int(vararg keys: String): Int? {
    val obj = this ?: return null
    for (key in keys) {
        obj[key].intOrNullValue()?.let { return it }
    }
    return null
}

fun JsonObject?.long(vararg keys: String): Long? {
    val obj = this ?: return null
    for (key in keys) {
        obj[key].longOrNullValue()?.let { return it }
    }
    return null
}

fun JsonObject?.double(vararg keys: String): Double? {
    val obj = this ?: return null
    for (key in keys) {
        obj[key].doubleOrNullValue()?.let { return it }
    }
    return null
}

fun JsonPrimitive?.orNullString(): String? = this?.contentOrNull?.takeIf { it.isNotBlank() }
