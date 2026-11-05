package com.joystream.app.data.providers.moviebox

import android.content.Context
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Visitor session issued by `visitor-login`, cached on disk until it expires. */
data class MovieBoxSession(
    val token: String,
    val userId: String? = null,
    val expiresAtSec: Long? = null,
    val createdAtSec: Long = System.currentTimeMillis() / 1000,
) {
    fun isValid(): Boolean {
        if (token.isBlank()) return false
        val now = System.currentTimeMillis() / 1000
        val expiry = expiresAtSec
        return if (expiry != null) now + 60 < expiry else now < createdAtSec + 7 * 24 * 3600
    }

    companion object {
        /** Reads `uid`/`userId`/`sub` and `exp` from the JWT payload. */
        fun fromToken(token: String, explicitUserId: String? = null): MovieBoxSession {
            val (jwtUid, jwtExp) = parseJwt(token)
            return MovieBoxSession(
                token = token,
                userId = explicitUserId ?: jwtUid,
                expiresAtSec = jwtExp,
            )
        }

        private fun parseJwt(token: String): Pair<String?, Long?> {
            val payload = token.split('.').getOrNull(1) ?: return null to null
            val decoded = runCatching {
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            }.getOrNull() ?: return null to null

            val json = runCatching {
                Json.parseToJsonElement(String(decoded, Charsets.UTF_8)) as JsonObject
            }.getOrNull() ?: return null to null

            val uid = json["userId"] ?: json["uid"] ?: json["sub"]
            val exp = json["exp"]?.jsonPrimitive?.longOrNull
            return uid?.jsonPrimitive?.contentOrNull to exp
        }
    }
}

class MovieBoxSessionStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("moviebox_session", Context.MODE_PRIVATE)

    fun load(): MovieBoxSession? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return MovieBoxSession(
            token = token,
            userId = prefs.getString(KEY_UID, null),
            expiresAtSec = prefs.getLong(KEY_EXPIRES, 0L).takeIf { it > 0L },
            createdAtSec = prefs.getLong(KEY_CREATED, System.currentTimeMillis() / 1000),
        )
    }

    fun save(session: MovieBoxSession) {
        prefs.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_UID, session.userId)
            .putLong(KEY_EXPIRES, session.expiresAtSec ?: 0L)
            .putLong(KEY_CREATED, session.createdAtSec)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_UID = "uid"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_CREATED = "created_at"
    }
}
