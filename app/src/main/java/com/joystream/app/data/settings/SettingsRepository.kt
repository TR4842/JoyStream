package com.joystream.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val enabledProviders: Set<String> = DEFAULT_PROVIDERS,
    val preferredQuality: String = "auto",
    val autoCheckUpdates: Boolean = true,
    val autoDownloadUpdates: Boolean = true,
    val notifyUpstreamChanges: Boolean = true,
    val wifiOnlyDownloads: Boolean = false,
    val liveTvUrl: String = "",
    val lastUpdateCheck: Long = 0L,
    val onboardingDone: Boolean = false,
) {
    fun isProviderEnabled(providerId: String): Boolean = enabledProviders.contains(providerId)

    companion object {
        val DEFAULT_PROVIDERS = setOf("moviebox", "dramachi")
        val QUALITY_OPTIONS = listOf("auto", "2160", "1080", "720", "480")
    }
}

/**
 * Lightweight settings store backed by SharedPreferences with a StateFlow view
 * for Compose + services to observe.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("joystream_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    private fun read(): Settings {
        val providers = prefs.getStringSet(KEY_PROVIDERS, null)
            ?.takeIf { it.isNotEmpty() }
            ?: Settings.DEFAULT_PROVIDERS
        return Settings(
            enabledProviders = providers,
            preferredQuality = prefs.getString(KEY_QUALITY, "auto") ?: "auto",
            autoCheckUpdates = prefs.getBoolean(KEY_AUTO_CHECK, true),
            autoDownloadUpdates = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true),
            notifyUpstreamChanges = prefs.getBoolean(KEY_UPSTREAM_NOTIFY, true),
            wifiOnlyDownloads = prefs.getBoolean(KEY_WIFI_ONLY, false),
            liveTvUrl = prefs.getString(KEY_LIVE_TV, "") ?: "",
            lastUpdateCheck = prefs.getLong(KEY_LAST_CHECK, 0L),
            onboardingDone = prefs.getBoolean(KEY_ONBOARDING, false),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        prefs.edit()
            .putStringSet(KEY_PROVIDERS, next.enabledProviders)
            .putString(KEY_QUALITY, next.preferredQuality)
            .putBoolean(KEY_AUTO_CHECK, next.autoCheckUpdates)
            .putBoolean(KEY_AUTO_DOWNLOAD, next.autoDownloadUpdates)
            .putBoolean(KEY_UPSTREAM_NOTIFY, next.notifyUpstreamChanges)
            .putBoolean(KEY_WIFI_ONLY, next.wifiOnlyDownloads)
            .putString(KEY_LIVE_TV, next.liveTvUrl)
            .putLong(KEY_LAST_CHECK, next.lastUpdateCheck)
            .putBoolean(KEY_ONBOARDING, next.onboardingDone)
            .apply()
        _state.value = next
    }

    fun toggleProvider(providerId: String) = update { settings ->
        val enabled = settings.enabledProviders.toMutableSet()
        if (!enabled.remove(providerId)) enabled.add(providerId)
        if (enabled.isEmpty()) settings else settings.copy(enabledProviders = enabled)
    }

    fun setLiveTvUrl(url: String) = update { it.copy(liveTvUrl = url.trim()) }

    fun setQuality(quality: String) = update { it.copy(preferredQuality = quality) }

    fun markUpdateChecked() = update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }

    fun completeOnboarding() = update { it.copy(onboardingDone = true) }

    private companion object {
        const val KEY_PROVIDERS = "providers"
        const val KEY_QUALITY = "quality"
        const val KEY_AUTO_CHECK = "auto_check_updates"
        const val KEY_AUTO_DOWNLOAD = "auto_download_updates"
        const val KEY_UPSTREAM_NOTIFY = "upstream_notify"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_LIVE_TV = "live_tv_url"
        const val KEY_LAST_CHECK = "last_update_check"
        const val KEY_ONBOARDING = "onboarding_done"
    }
}
