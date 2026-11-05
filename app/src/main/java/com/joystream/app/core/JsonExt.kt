package com.joystream.app.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Small, forgiving JSON accessors. Provider payloads drift over time: a field
 * can arrive as a string, a number or be missing entirely, so every accessor
 * here degrades to null instead of throwing. All of them accept a nullable
 * JsonElement, which keeps mapping code free of `?.` noise.
 */

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

fun JsonElement?.stringOrNull(): String? {
    val element = this ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
}

fun JsonElement?.intOrNullValue(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.contentOrNull?.toIntOrNull()?.let { return it }
    return primitive.doubleOrNull?.toInt()
}

fun JsonElement?.longOrNullValue(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.longOrNull?.let { return it }
    return primitive.contentOrNull?.toDoubleOrNull()?.toLong()
}

fun JsonElement?.doubleOrNullValue(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.doubleOrNull?.let { return it }
    return primitive.contentOrNull?.toDoubleOrNull()
}

fun JsonElement?.boolOrNullValue(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    return when (primitive.contentOrNull?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

/** First present, non-blank string among [keys]. */
fun JsonElement?.str(vararg keys: String): String? {
    val obj = this.asObj() ?: return null
    for (key in keys) {
        obj[key].stringOrNull()?.let { return it }
    }
    return null
}

fun JsonElement?.obj(vararg keys: String): JsonObject? {
    val obj = this.asObj() ?: return null
    for (key in keys) {
        (obj[key] as? JsonObject)?.let { return it }
    }
    return null
}

fun JsonElement?.arr(vararg keys: String): JsonArray? {
    val obj = this.asObj() ?: return null
    for (key in keys) {
        (obj[key] as? JsonArray)?.let { return it }
    }
    return null
}

fun JsonElement?.int(vararg keys: String): Int? {
    val obj = this.asObj() ?: return null
    for (key in keys) {
        obj[key].intOrNullValue()?.let { return it }
    }
    return null
}

fun JsonElement?.long(vararg keys: String): Long? {
    val obj = this.asObj() ?: return null
    for (key in keys) {
        obj[key].longOrNullValue()?.let { return it }
    }
    return null
}

fun JsonElement?.double(vararg keys: String): Double? {
    val obj = this.asObj() ?: return null
    for (key in keys) {
        obj[key].doubleOrNullValue()?.let { return it }
    }
    return null
}

fun JsonPrimitive?.orNullString(): String? = this?.contentOrNull?.takeIf { it.isNotBlank() }
