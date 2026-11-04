package com.joystream.app.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP stack. Provider calls and ExoPlayer both reuse these clients so
 * connections and the cookie-less session model stay consistent.
 */
object Http {

    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Longer read timeout for media transfers (APK updates, downloads). */
    val downloadClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun request(url: String, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_USER_AGENT)
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .build()
}
