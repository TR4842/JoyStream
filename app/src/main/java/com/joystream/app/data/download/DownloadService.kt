package com.joystream.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.joystream.app.JoyStreamApp
import com.joystream.app.MainActivity
import com.joystream.app.R
import com.joystream.app.core.Http
import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.DownloadItem
import com.joystream.app.data.model.DownloadStatus
import com.joystream.app.data.model.MediaType
import com.joystream.app.data.model.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Foreground service that downloads one queued item at a time using HTTP range
 * requests so paused / interrupted transfers resume where they left off.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    @Volatile
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    private val container by lazy { (application as JoyStreamApp).container }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                paused = true
                scope.launch {
                    container.libraryStore.setAllDownloadsStatus(
                        DownloadStatus.DOWNLOADING,
                        DownloadStatus.PAUSED,
                    )
                    container.libraryStore.setAllDownloadsStatus(
                        DownloadStatus.QUEUED,
                        DownloadStatus.PAUSED,
                    )
                }
                updateNotification("Downloads paused", 0, indeterminate = false)
                return START_NOT_STICKY
            }

            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                if (id != null) {
                    scope.launch { cancelDownload(id) }
                }
                return START_NOT_STICKY
            }

            else -> Unit
        }

        startForegroundCompat(getString(R.string.download_channel_name))
        paused = false
        scope.launch {
            container.libraryStore.setAllDownloadsStatus(
                DownloadStatus.PAUSED,
                DownloadStatus.QUEUED,
            )
        }

        if (worker?.isActive != true) {
            worker = scope.launch { processQueue() }
        }
        return START_NOT_STICKY
    }

    private suspend fun processQueue() {
        val store = container.libraryStore
        var processedAny = false
        while (scope.isActive) {
            if (paused) {
                delay(400)
                continue
            }
            val next = store.snapshot.value.downloads.firstOrNull {
                it.statusEnum == DownloadStatus.QUEUED
            } ?: break

            if (container.settings.current.wifiOnlyDownloads && !isOnWifi()) {
                store.updateDownload(next.id) {
                    it.copy(
                        status = DownloadStatus.PAUSED.name,
                        error = "Waiting for Wi-Fi",
                    )
                }
                updateNotification("Waiting for Wi-Fi", 0)
                delay(5_000)
                continue
            }

            processedAny = true
            downloadItem(next)
        }

        if (processedAny) {
            updateNotification("Downloads finished", 100)
            delay(1_500)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun downloadItem(item: DownloadItem) {
        val store = container.libraryStore
        val directory = downloadDirectory(this)
        val finalFile = File(directory, item.fileName)
        val partFile = File(directory, "${item.fileName}.part")

        store.updateDownload(item.id) {
            it.copy(status = DownloadStatus.DOWNLOADING.name, error = null)
        }

        if (finalFile.exists() && finalFile.length() > 0) {
            markCompleted(item.id, finalFile)
            return
        }

        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        val builder = Request.Builder()
            .url(item.url)
            .header("User-Agent", Http.MOBILE_USER_AGENT)
        item.headers.forEach { (key, value) -> builder.header(key, value) }
        if (existingBytes > 0) {
            builder.header("Range", "bytes=$existingBytes-")
        }

        try {
            Http.downloadClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response")
                val appending = response.code == 206 && existingBytes > 0
                val startingBytes = if (appending) existingBytes else 0L
                val contentLength = body.contentLength().takeIf { it > 0 } ?: 0L
                val totalBytes = if (contentLength > 0) startingBytes + contentLength else item.totalBytes

                val output = RandomAccessFile(partFile, "rw")
                output.seek(startingBytes)
                if (!appending) output.setLength(0)

                var downloaded = startingBytes
                var lastPersisted = 0L
                var lastNotified = 0L
                val buffer = ByteArray(256 * 1024)

                body.byteStream().use { input ->
                    output.use {
                        while (scope.isActive) {
                            if (paused) {
                                delay(300)
                                continue
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastPersisted > 1_000) {
                                lastPersisted = now
                                store.updateDownload(item.id) {
                                    it.copy(
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes,
                                        status = DownloadStatus.DOWNLOADING.name,
                                    )
                                }
                            }
                            if (now - lastNotified > 1_500) {
                                lastNotified = now
                                val progress = if (totalBytes > 0) {
                                    ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                }
                                updateNotification(item.title, progress, indeterminate = totalBytes == 0L)
                            }
                        }
                    }
                }

                store.updateDownload(item.id) {
                    it.copy(downloadedBytes = downloaded, totalBytes = totalBytes)
                }

                if (downloaded > 0) {
                    if (partFile.exists()) {
                        if (finalFile.exists()) finalFile.delete()
                        partFile.renameTo(finalFile)
                    }
                    markCompleted(item.id, finalFile)
                } else {
                    throw IOException("Transfer produced no data")
                }
            }
        } catch (error: Exception) {
            val message = error.message ?: "Download failed"
            store.updateDownload(item.id) {
                it.copy(
                    status = DownloadStatus.FAILED.name,
                    error = message,
                    downloadedBytes = if (partFile.exists()) partFile.length() else it.downloadedBytes,
                )
            }
        }
    }

    private suspend fun markCompleted(id: String, file: File) {
        val store = container.libraryStore
        val item = store.download(id)
        store.updateDownload(id) {
            it.copy(
                status = DownloadStatus.COMPLETED.name,
                downloadedBytes = file.length(),
                totalBytes = file.length().takeIf { size -> size > 0 } ?: it.totalBytes,
                completedAt = System.currentTimeMillis(),
                localPath = file.absolutePath,
                error = null,
            )
        }
        if (item != null) {
            store.recordPlayback(
                details = null,
                item = CatalogItem(
                    provider = item.provider,
                    idValue = item.idValue,
                    title = item.title,
                    mediaType = MediaType.UNKNOWN,
                    posterUrl = item.posterUrl,
                ),
                provider = item.provider,
                idValue = item.idValue,
                positionMs = 0L,
                durationMs = 0L,
                season = item.season,
                episode = item.episode,
                episodeTitle = item.episodeLabel,
                localPath = file.absolutePath,
            )
        }
    }

    private suspend fun cancelDownload(id: String) {
        val item = container.libraryStore.download(id) ?: return
        val directory = downloadDirectory(this)
        File(directory, "${item.fileName}.part").delete()
        File(directory, item.fileName).delete()
        container.libraryStore.removeDownload(id)
    }

    private fun isOnWifi(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text, 0, indeterminate = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String, progress: Int, indeterminate: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress, indeterminate))
    }

    private fun buildNotification(
        text: String,
        progress: Int,
        indeterminate: Boolean = false,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, progress.coerceIn(0, 100), indeterminate)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.joystream.app.download.START"
        const val ACTION_PAUSE = "com.joystream.app.download.PAUSE"
        const val ACTION_CANCEL = "com.joystream.app.download.CANCEL"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4101
    }
}

/** Convenience helper so UI code never needs to know about service internals. */
fun DownloadItem.progressLabel(): String =
    if (totalBytes > 0) "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
    else formatBytes(downloadedBytes)
