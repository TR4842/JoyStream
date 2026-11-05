package com.joystream.app.data.providers.moviebox

import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Request signing for the MovieBox mobile BFF.
 *
 * Every call carries:
 *  - `x-client-token`: "<timestampMs>,<md5(reversed timestamp)>"
 *  - `x-tr-signature`: "<timestampMs>|2|<base64(HMAC-MD5(canonical))>"
 *  - `x-client-info`: a JSON blob that mimics the official Android client
 *
 * The canonical string is newline joined:
 *   METHOD, accept, content-type, body length, timestamp, md5(body), canonical url
 * where the canonical url is the path plus lexicographically sorted query pairs.
 */
object MovieBoxCrypto {

    /**
     * Signing keys, base64(double base64). The provider rotates these whenever
     * the client is updated, so both the current and the previous key are kept
     * and the client retries with the other one on a signature rejection.
     */
    private const val SECRET_KEY_PRIMARY_B64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
    private const val SECRET_KEY_PREVIOUS_B64 = "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="

    val SECRET_BYTES: ByteArray = decodeSecret(SECRET_KEY_PRIMARY_B64)
    private val PREVIOUS_SECRET_BYTES: ByteArray = decodeSecret(SECRET_KEY_PREVIOUS_B64)

    private fun decodeSecret(encoded: String): ByteArray =
        base64Decode(base64Decode(encoded).decodeToString())

    private const val SIGNATURE_BODY_MAX_BYTES = 102_400
    private const val PACKAGE_NAME = "com.community.mbox.in"
    private const val VERSION_NAME = "4.0.03.0920.03"

    fun md5Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun generateClientToken(timestampMs: Long): String {
        val stamp = timestampMs.toString()
        val reversed = stamp.reversed()
        return "$stamp,${md5Hex(reversed.toByteArray())}"
    }

    /** Path plus query pairs sorted by key (values keep their original order). */
    fun canonicalUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val names = parsed.queryParameterNames.sorted()
        if (names.isEmpty()) return parsed.encodedPath
        val query = buildString {
            var first = true
            for (name in names) {
                for (value in parsed.queryParameterValues(name)) {
                    if (!first) append('&')
                    append(name).append('=').append(value)
                    first = false
                }
            }
        }
        return "${parsed.encodedPath}?$query"
    }

    fun buildCanonicalString(
        method: String,
        accept: String,
        contentType: String,
        url: String,
        body: String?,
        timestampMs: Long,
    ): String {
        val bytes = body?.toByteArray()
        val bodyHash: String
        val bodyLength: String
        if (bytes != null) {
            val truncated = if (bytes.size > SIGNATURE_BODY_MAX_BYTES) {
                bytes.copyOfRange(0, SIGNATURE_BODY_MAX_BYTES)
            } else {
                bytes
            }
            bodyHash = md5Hex(truncated)
            bodyLength = bytes.size.toString()
        } else {
            bodyHash = ""
            bodyLength = ""
        }
        return listOf(
            method.uppercase(),
            accept,
            contentType,
            bodyLength,
            timestampMs.toString(),
            bodyHash,
            canonicalUrl(url),
        ).joinToString("\n")
    }

    fun generateSignature(
        method: String,
        accept: String,
        contentType: String,
        url: String,
        body: String?,
        timestampMs: Long,
        usePreviousKey: Boolean = false,
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestampMs)
        val mac = Mac.getInstance("HmacMD5")
        val key = if (usePreviousKey) PREVIOUS_SECRET_BYTES else SECRET_BYTES
        mac.init(SecretKeySpec(key, "HmacMD5"))
        val signature = base64Encode(mac.doFinal(canonical.toByteArray()))
        return "$timestampMs|2|$signature"
    }

    /** Signed header map for a request. */
    fun signedHeaders(
        method: String,
        url: String,
        body: String?,
        token: String?,
        userAgent: String,
        clientInfo: String,
        spoofedIp: String,
        timestampMs: Long = System.currentTimeMillis(),
        usePreviousKey: Boolean = false,
    ): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Connection" to "keep-alive",
            "x-client-token" to generateClientToken(timestampMs),
            "x-tr-signature" to generateSignature(
                method = method,
                accept = "application/json",
                contentType = "application/json",
                url = url,
                body = body,
                timestampMs = timestampMs,
                usePreviousKey = usePreviousKey,
            ),
            "x-client-info" to clientInfo,
            "x-client-status" to "0",
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    /** Builds a plausible device fingerprint, mirroring the official client. */
    fun generateClientIdentity(): ClientIdentity {
        val androidVersions = listOf(
            "9" to "PQ3A.190605.03081104",
            "10" to "QP1A.191005.007.A3",
            "11" to "RP1A.200720.011",
            "12" to "S1B.220414.015",
            "13" to "TQ2A.230405.003",
        )
        val devices = listOf(
            "23078RKD5C", "2201117TY", "2201117TG", "22101316G",
            "21121210G", "M2012K11AG", "M2007J20CG",
        )
        val versionCodes = listOf(50020130)
        val networks = listOf("NETWORK_WIFI", "NETWORK_MOBILE")
        val timezones = listOf(
            "Asia/Kolkata", "Asia/Shanghai", "Asia/Tokyo", "Asia/Dhaka",
            "America/New_York", "Europe/London",
        )

        val android = androidVersions.random()
        val device = devices.random()
        val versionCode = versionCodes.random()
        val network = networks.random()
        val timezone = timezones.random()

        val userAgent = "$PACKAGE_NAME/$versionCode (Linux; U; Android ${android.first}; " +
            "en_US; $device; Build/${android.second}; Cronet/145.0.7582.0)"

        val clientInfo = buildString {
            append("{\"package_name\":\"").append(PACKAGE_NAME).append("\",")
            append("\"version_name\":\"").append(VERSION_NAME).append("\",")
            append("\"version_code\":").append(versionCode).append(',')
            append("\"os\":\"android\",")
            append("\"os_version\":\"").append(android.first).append("\",")
            append("\"device_id\":\"").append(randomHex(32)).append("\",")
            append("\"install_store\":\"official\",")
            append("\"gaid\":\"").append(randomUuid()).append("\",")
            append("\"brand\":\"Google\",")
            append("\"model\":\"").append(device).append("\",")
            append("\"system_language\":\"en\",")
            append("\"net\":\"").append(network).append("\",")
            append("\"region\":\"IN\",")
            append("\"timezone\":\"").append(timezone).append("\",")
            append("\"sp_code\":\"\"}")
        }

        return ClientIdentity(userAgent = userAgent, clientInfo = clientInfo, spoofedIp = randomSpoofedIp())
    }

    data class ClientIdentity(
        val userAgent: String,
        val clientInfo: String,
        val spoofedIp: String,
    )

    private fun randomHex(length: Int): String {
        val chars = "0123456789abcdef"
        return buildString { repeat(length) { append(chars[Random.nextInt(16)]) } }
    }

    private fun randomUuid(): String = listOf(8, 4, 4, 4, 12).joinToString("-") { randomHex(it) }

    private fun randomSpoofedIp(): String {
        val prefixes = listOf(
            "103.241", "49.36", "117.195", "106.198", "122.162",
            "157.32", "182.70", "103.58", "27.60", "59.90",
        )
        return "${prefixes.random()}.${Random.nextInt(1, 254)}.${Random.nextInt(1, 254)}"
    }

    /** Standard base64 (RFC 4648) with padding — pure JVM so it stays unit testable. */
    fun base64Encode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val builder = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0

            builder.append(alphabet[b0 shr 2])
            builder.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (index + 1 < bytes.size) {
                builder.append(alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)])
            } else {
                builder.append('=')
            }
            if (index + 2 < bytes.size) {
                builder.append(alphabet[b2 and 0x3F])
            } else {
                builder.append('=')
            }
            index += 3
        }
        return builder.toString()
    }

    /**
     * Standard base64 decoder (RFC 4648), padding tolerant and pure JVM.
     * `java.util.Base64` is API 26+ and `android.util.Base64` is unavailable in
     * unit tests, so the small decode lives here.
     */
    fun base64Decode(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val normalized = value.replace('-', '+').replace('_', '/')
        val output = java.io.ByteArrayOutputStream(normalized.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (char in normalized) {
            if (char == '=' || char.isWhitespace()) continue
            val index = alphabet.indexOf(char)
            if (index < 0) continue
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xFF)
            }
        }
        return output.toByteArray()
    }

    /** Decodes a base64 blob tolerating URL-safe alphabets and missing padding. */
    fun decodeBase64Loose(value: String): String? {
        val normalized = value.replace('-', '+').replace('_', '/').trim()
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Extracts a DASH manifest URL buried in a CloudFront signed cookie. */
    fun dashManifestFromPolicy(signCookie: String): String? {
        for (rawPart in signCookie.split(';')) {
            val part = rawPart.trim()
            val prefixIndex = part.indexOf("urlprefix=")
            if (prefixIndex >= 0) {
                val rest = part.substring(prefixIndex + "urlprefix=".length)
                val token = rest.substringBefore(':').trim()
                decodeBase64Loose(token)?.let { decoded ->
                    dashFromUrl(decoded)?.let { return it }
                }
            }
            if (part.startsWith("CloudFront-Policy=")) {
                val raw = part.removePrefix("CloudFront-Policy=").trim()
                val json = decodeBase64Loose(raw) ?: continue
                val resource = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(json)
                        .let { it as? JsonObject }
                        ?.get("Statement")?.let { st ->
                            (st as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
                        }?.get("Resource") as? JsonPrimitive
                }.getOrNull()?.contentOrNullSafe()
                if (resource != null) dashFromUrl(resource)?.let { return it }
            }
        }
        return null
    }

    private fun JsonPrimitive?.contentOrNullSafe(): String? = this?.content?.takeIf { it.isNotBlank() }

    private fun dashFromUrl(value: String): String? {
        val base = value.trim().trimEnd('*').trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        if (!base.contains("/dash/")) return null
        return "$base/index.mpd"
    }
}
