package com.joystream.app.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.joystream.app.BuildConfig
import com.joystream.app.core.Http
import com.joystream.app.core.arr
import com.joystream.app.core.asArr
import com.joystream.app.core.asObj
import com.joystream.app.core.long
import com.joystream.app.core.obj
import com.joystream.app.core.str
import com.joystream.app.core.util.compareVersions
import com.joystream.app.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.File
import java.io.IOException

data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val notes: String,
    val apkUrl: String?,
    val apkSize: Long,
    val publishedAt: String?,
    val releaseUrl: String,
)

/** Latest commit of the upstream MovieBox-Tui repository. */
data class UpstreamStatus(
    val sha: String,
    val date: String?,
    val message: String?,
    val builtSha: String,
) {
    val shortSha: String get() = sha.take(7)
    val builtShortSha: String get() = if (builtSha.isBlank()) "unbundled" else builtSha.take(7)

    /** True when upstream moved since this APK was produced. */
    val isNewerThanBuild: Boolean
        get() = builtSha.isNotBlank() && !sha.startsWith(builtSha.take(7))
}

sealed interface InstallResult {
    data object Started : InstallResult
    data object NeedsPermission : InstallResult
    data class Failed(val message: String) : InstallResult
}

/**
 * In-app updates.
 *
 * Two independent things are tracked:
 *  1. JoyStream releases (the APK itself) — parsed from GitHub Releases and
 *     installed through PackageInstaller, no browser round-trip.
 *  2. Upstream MovieBox-Tui commits — the scraping endpoints live there, and CI
 *     ({@code .github/workflows/upstream-sync.yml}) rebuilds + re-releases
 *     JoyStream whenever upstream moves, so devices pick the new build up here.
 */
class UpdateRepository(
    context: Context,
    private val settings: SettingsRepository,
) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val payload = githubGet("https://api.github.com/repos/${BuildConfig.APP_REPO}/releases/latest")
            ?: return@withContext null
        val tag = payload.str("tag_name") ?: return@withContext null
        val versionName = tag.removePrefix("v").removePrefix("V").trim()
        if (compareVersions(versionName, BuildConfig.VERSION_NAME) <= 0) return@withContext null

        val apkAsset = payload.arr("assets")?.mapNotNull { it.asObj() }
            ?.firstOrNull { asset -> asset.str("name")?.endsWith(".apk", ignoreCase = true) == true }

        UpdateInfo(
            versionName = versionName,
            tagName = tag,
            notes = payload.str("body") ?: "",
            apkUrl = apkAsset?.str("browser_download_url"),
            apkSize = apkAsset?.long("size") ?: 0L,
            publishedAt = payload.str("published_at", "created_at"),
            releaseUrl = payload.str("html_url") ?: "",
        ).also { settings.markUpdateChecked() }
    }

    suspend fun latestUpstreamCommit(): UpstreamStatus? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/${BuildConfig.UPSTREAM_REPO}/commits?per_page=1"
        val array = runCatching {
            val body = githubGetRaw(url) ?: return@withContext null
            Json.parseToJsonElement(body) as? JsonArray
        }.getOrNull() ?: JsonArray(emptyList())

        val commit = array.firstOrNull()?.asObj() ?: return@withContext null
        val sha = commit.str("sha") ?: return@withContext null
        val commitInfo = commit.obj("commit")
        UpstreamStatus(
            sha = sha,
            date = commitInfo?.obj("committer")?.str("date")
                ?: commitInfo?.obj("author")?.str("date"),
            message = commitInfo?.str("message")?.lineSequence()?.firstOrNull(),
            builtSha = BuildConfig.UPSTREAM_SHA,
        )
    }

    suspend fun downloadApk(
        update: UpdateInfo,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val url = update.apkUrl ?: throw IOException("This release has no APK attached yet")
        val directory = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "joystream-${update.versionName}.apk")
        if (target.exists() && target.length() > 0 && target.length() == update.apkSize) {
            return@withContext target
        }

        val request = Request.Builder().url(url).header("User-Agent", Http.MOBILE_USER_AGENT).build()
        val response = Http.downloadClient.newCall(request).execute()
        response.use { result ->
            if (!result.isSuccessful) throw IOException("Download failed (HTTP ${result.code})")
            val body = result.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: update.apkSize
            val temp = File(directory, "${target.name}.part")
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var written = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200) {
                            lastUpdate = now
                            onProgress(written, total)
                        }
                    }
                    onProgress(written, total)
                }
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }
        target
    }

    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun unknownSourcesSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    /** Installs a downloaded APK through a PackageInstaller session. */
    fun installApk(file: File): InstallResult {
        if (!file.exists()) return InstallResult.Failed("The update file is missing")
        if (!canInstallPackages()) return InstallResult.NeedsPermission

        return runCatching {
            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(appContext.packageName)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("joystream-update", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callbackIntent = Intent(appContext, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_INSTALL_RESULT)
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
            InstallResult.Started
        }.getOrElse { error ->
            InstallResult.Failed(error.message ?: "Could not start the installer")
        }
    }

    /** Fallback: open the APK with the system package installer UI. */
    fun openInstallerExternally(file: File): Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun githubGet(url: String): JsonObject? = runCatching {
        val body = githubGetRaw(url) ?: return null
        Json.parseToJsonElement(body) as? JsonObject
    }.getOrNull()

    private fun githubGetRaw(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "JoyStream/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull()
    }
}
