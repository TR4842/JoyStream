package com.joystream.app.data.providers.moviebox

import com.joystream.app.core.asObj
import com.joystream.app.core.str
import com.joystream.app.data.providers.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Signed HTTP client for the MovieBox mobile BFF, including visitor-session
 * management and multi-host failover (mirrors the upstream Rust client).
 */
class MovieBoxClient(
    private val okHttp: OkHttpClient,
    private val sessionStore: MovieBoxSessionStore,
) {

    private val identity = MovieBoxCrypto.generateClientIdentity()
    val userAgent: String get() = identity.userAgent

    @Volatile
    private var activeHostIndex = 0

    private val sessionMutex = Mutex()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun get(pathAndQuery: String): JsonElement = request("GET", pathAndQuery, null)

    suspend fun post(pathAndQuery: String, body: String): JsonElement =
        request("POST", pathAndQuery, body)

    private suspend fun request(
        method: String,
        pathAndQuery: String,
        body: String?,
    ): JsonElement {
        val token = ensureToken()
        return try {
            requestHosts(method, pathAndQuery, body, token)
        } catch (error: ProviderException) {
            // Session may have expired server-side; refresh once and retry.
            sessionStore.clear()
            val fresh = ensureToken(force = true)
            requestHosts(method, pathAndQuery, body, fresh)
        }
    }

    private suspend fun ensureToken(force: Boolean = false): String {
        if (!force) {
            val cached = sessionStore.load()
            if (cached != null && cached.isValid()) return cached.token
        }
        return sessionMutex.withLock {
            if (!force) {
                val cached = sessionStore.load()
                if (cached != null && cached.isValid()) return@withLock cached.token
            }
            val payload = requestHosts("POST", VISITOR_LOGIN, "{}", null)
            val obj = payload.asObj() ?: throw ProviderException("MovieBox login returned no payload")
            val token = obj.str("token")
                ?: throw ProviderException("MovieBox login returned no token")
            val session = MovieBoxSession.fromToken(token, obj.str("uid", "userId"))
            sessionStore.save(session)
            token
        }
    }

    private suspend fun requestHosts(
        method: String,
        pathAndQuery: String,
        body: String?,
        authToken: String?,
    ): JsonElement = withContext(Dispatchers.IO) {
        val startIndex = activeHostIndex
        var backoffMs = 50L
        var lastError: String? = null

        for (attempt in HOSTS.indices) {
            if (attempt > 0) {
                delay(backoffMs)
                backoffMs = 50L
            }
            val index = (startIndex + attempt) % HOSTS.size
            val url = HOSTS[index] + pathAndQuery

            val headers = MovieBoxCrypto.signedHeaders(
                method = method,
                url = url,
                body = body,
                token = authToken,
                userAgent = identity.userAgent,
                clientInfo = identity.clientInfo,
                spoofedIp = identity.spoofedIp,
            )

            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) -> requestBuilder.header(key, value) }

            val request = when (method.uppercase()) {
                "POST" -> requestBuilder.post((body ?: "{}").toRequestBody(jsonMediaType)).build()
                else -> requestBuilder.get().build()
            }

            try {
                okHttp.newCall(request).execute().use { response ->
                    absorbSessionHeader(response.header("x-user"))

                    if (response.code in RETRY_STATUS_CODES) {
                        lastError = "HTTP ${response.code}"
                        activeHostIndex = (index + 1) % HOSTS.size
                        if (response.code == 429) {
                            backoffMs = (response.header("Retry-After")?.toLongOrNull() ?: 0L)
                                .coerceAtMost(3L) * 1000L
                            if (backoffMs <= 0L) backoffMs = 400L
                        }
                        return@use
                    }

                    if (!response.isSuccessful) {
                        throw ProviderException(
                            "MovieBox responded with HTTP ${response.code}",
                            statusCode = response.code,
                        )
                    }

                    val text = response.body?.string().orEmpty()
                    if (text.isBlank()) throw IOException("empty MovieBox response")

                    activeHostIndex = index
                    val parsed = runCatching { Json.parseToJsonElement(text) }.getOrElse {
                        throw ProviderException("MovieBox returned malformed JSON")
                    }
                    // The BFF wraps payloads in {"data": ...}; unwrap when present.
                    val obj = parsed as? JsonObject
                    return@withContext obj?.get("data") ?: parsed
                }
            } catch (io: IOException) {
                lastError = io.message
                activeHostIndex = (index + 1) % HOSTS.size
            }
        }

        throw ProviderException(
            "MovieBox is unreachable right now" + (lastError?.let { " ($it)" } ?: ""),
        )
    }

    /** Some responses rotate the visitor token through the `x-user` header. */
    private fun absorbSessionHeader(raw: String?) {
        if (raw.isNullOrBlank()) return
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        val token = obj.str("token") ?: return
        if (token.isNotBlank()) {
            sessionStore.save(MovieBoxSession.fromToken(token, obj.str("uid", "userId")))
        }
    }

    companion object {
        val HOSTS = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com",
            "https://api6sg.aoneroom.com",
            "https://api.inmoviebox.com",
        )

        private const val VISITOR_LOGIN = "/wefeed-mobile-bff/user-api/visitor-login"

        private val RETRY_STATUS_CODES = setOf(403, 406, 407, 429, 500, 502, 503, 504)
    }
}
