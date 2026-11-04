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

    private val SECRET_BYTES = byteArrayOf(
        0xEF.toByte(), 0xA8.toByte(), 0x91.toByte(), 0x97.toByte(),
        0x4E, 0xEC.toByte(), 0xD3.toByte(), 0x14,
        0x8D.toByte(), 0xF6.toByte(), 0x3A, 0xA6.toByte(),
        0x11, 0x60, 0x2D, 0xEF.toByte(),
        0xD1.toByte(), 0x01, 0x25, 0x9B.toByte(),
        0xA5.toByte(), 0x21, 0x02, 0x2C,
        0x57, 0xAE.toByte(), 0x05, 0x66,
        0xBD.toByte(), 0x8E.toByte(),
    )

    private const val SIGNATURE_BODY_MAX_BYTES = 102_400
    private const val PACKAGE_NAME = "com.community.oneroom"
    private const val VERSION_NAME = "4.0.01.0813.03"

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
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestampMs)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
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
            ),
            "x-client-info" to clientInfo,
            "x-client-status" to "0",
            "x-forwarded-for" to spoofedIp,
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
        val versionCodes = listOf(50020117, 50020118, 50020119, 50020120, 50020121)
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
            "en_US; $device; Build/${android.second}; Cronet/135.0.7012.3)"

        val clientInfo = buildString {
            append("{\"package_name\":\"").append(PACKAGE_NAME).append("\",")
            append("\"version_name\":\"").append(VERSION_NAME).append("\",")
            append("\"version_code\":").append(versionCode).append(',')
            append("\"os\":\"android\",")
            append("\"os_version\":\"").append(android.first).append("\",")
            append("\"install_ch\":\"ps\",")
            append("\"device_id\":\"").append(randomHex(32)).append("\",")
            append("\"install_store\":\"ps\",")
            append("\"gaid\":\"").append(randomUuid()).append("\",")
            append("\"brand\":\"Redmi\",")
            append("\"model\":\"").append(device).append("\",")
            append("\"system_language\":\"en\",")
            append("\"net\":\"").append(network).append("\",")
            append("\"region\":\"US\",")
            append("\"timezone\":\"").append(timezone).append("\",")
            append("\"sp_code\":\"40401\",")
            append("\"X-Play-Mode\":\"2\"}")
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
