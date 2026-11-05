package com.joystream.app.data.download

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.ContextCompat
import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.DownloadItem
import com.joystream.app.data.model.DownloadStatus
import com.joystream.app.data.model.MediaDetails
import com.joystream.app.data.model.StreamRelease
import com.joystream.app.core.util.slugifyFileName
import java.io.File
import java.util.UUID

/** Directory where finished media is stored (app-specific external storage). */
fun downloadDirectory(context: Context): File {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        ?: File(context.filesDir, "Movies")
    if (!base.exists()) base.mkdirs()
    return base
}

fun buildDownloadItem(
    release: StreamRelease,
    details: MediaDetails? = null,
    item: CatalogItem? = null,
    season: Int? = null,
    episode: Int? = null,
): DownloadItem {
    val extension = release.url.substringBefore('?')
        .substringAfterLast('.', "mp4")
        .lowercase()
        .let { if (it.length in 2..5 && it.all(Char::isLetterOrDigit)) it else "mp4" }

    val baseTitle = details?.title ?: item?.title ?: release.title
    val label = when {
        season != null && episode != null && season > 0 ->
            "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        episode != null && episode > 0 -> "E${episode.toString().padStart(2, '0')}"
        else -> null
    }
    val fileName = listOfNotNull(baseTitle, label, release.qualityLabel, release.codec)
        .joinToString(" ")
        .let(::slugifyFileName)
        .plus(".$extension")

    return DownloadItem(
        id = UUID.randomUUID().toString(),
        provider = release.provider,
        idValue = release.resourceId ?: details?.id?.value ?: item?.idValue.orEmpty(),
        title = baseTitle,
        episodeLabel = label,
        season = season?.takeIf { it > 0 },
        episode = episode?.takeIf { it > 0 },
        url = release.url,
        headers = release.headers,
        fileName = fileName,
        posterUrl = details?.posterUrl ?: item?.posterUrl,
        totalBytes = release.sizeBytes ?: 0L,
        status = DownloadStatus.QUEUED.name,
    )
}

/** Starts / stops the download foreground service. */
object DownloadManager {

    fun enqueue(context: Context, item: DownloadItem) {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_DOWNLOAD_ID, item.id)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun pauseAll(context: Context) {
        context.startService(
            Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_PAUSE
            },
        )
    }

    fun resumeAll(context: Context) {
        context.startService(
            Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START
            },
        )
    }

    fun cancel(context: Context, downloadId: String) {
        context.startService(
            Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_CANCEL
                putExtra(DownloadService.EXTRA_DOWNLOAD_ID, downloadId)
            },
        )
    }
}
