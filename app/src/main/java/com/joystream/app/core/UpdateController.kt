package com.joystream.app.core

import com.joystream.app.data.settings.SettingsRepository
import com.joystream.app.data.update.InstallResult
import com.joystream.app.data.update.UpdateInfo
import com.joystream.app.data.update.UpdateRepository
import com.joystream.app.data.update.UpstreamStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class UpdateState(
    val checking: Boolean = false,
    val available: UpdateInfo? = null,
    val downloadedFile: File? = null,
    val progress: Float = -1f,
    val error: String? = null,
    val upstream: UpstreamStatus? = null,
    val needsInstallPermission: Boolean = false,
    val lastChecked: Long = 0L,
)

/**
 * Coordinates update checks, APK download and installation, shared by the home
 * banner and the Settings screen.
 */
class UpdateController(
    private val repository: UpdateRepository,
    private val settings: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Called once at app start; respects the auto-check setting. */
    fun checkOnStartup() {
        if (!settings.current.autoCheckUpdates) return
        refresh(silent = true)
    }

    fun refresh(silent: Boolean = false) {
        if (_state.value.checking) return
        if (silent && System.currentTimeMillis() - _state.value.lastChecked < CACHE_WINDOW_MS) return
        _state.value = _state.value.copy(checking = true, error = null)
        scope.launch {
            val update = runCatching { repository.checkForUpdate() }
            val upstream = runCatching { repository.latestUpstreamCommit() }.getOrNull()
            _state.value = _state.value.copy(
                checking = false,
                available = update.getOrNull() ?: _state.value.available,
                upstream = upstream ?: _state.value.upstream,
                error = update.exceptionOrNull()?.message,
                needsInstallPermission = !repository.canInstallPackages(),
                lastChecked = System.currentTimeMillis(),
            )
            settings.markUpdateChecked()

            val info = update.getOrNull()
            if (info != null && settings.current.autoDownloadUpdates &&
                repository.canInstallPackages()
            ) {
                download()
            }
        }
    }

    fun download() {
        val info = _state.value.available ?: return
        if (_state.value.downloadedFile != null) return
        _state.value = _state.value.copy(progress = 0f, error = null)
        scope.launch {
            val file = runCatching {
                repository.downloadApk(info) { downloaded, total ->
                    val fraction = if (total > 0) {
                        (downloaded.toFloat() / total).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    _state.value = _state.value.copy(progress = fraction)
                }
            }
            _state.value = file.fold(
                onSuccess = { _state.value.copy(downloadedFile = it, progress = 1f) },
                onFailure = {
                    _state.value.copy(progress = -1f, error = it.message ?: "Download failed")
                },
            )
        }
    }

    fun install(): InstallResult {
        val file = _state.value.downloadedFile
            ?: return InstallResult.Failed("Update has not been downloaded yet")
        val result = repository.installApk(file)
        if (result is InstallResult.NeedsPermission) {
            _state.value = _state.value.copy(needsInstallPermission = true)
        }
        return result
    }

    fun installPermissionIntent() = repository.unknownSourcesSettingsIntent()

    fun externalInstallerIntent(file: File) = repository.openInstallerExternally(file)

    fun dismiss() = run { _state.value = _state.value.copy(available = null, progress = -1f) }

    fun clearError() = run { _state.value = _state.value.copy(error = null) }

    private companion object {
        const val CACHE_WINDOW_MS = 30 * 60 * 1000L
    }
}
