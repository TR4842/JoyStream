package com.joystream.app.core

import android.content.Context
import com.joystream.app.data.download.DownloadManager
import com.joystream.app.data.library.LibraryStore
import com.joystream.app.data.providers.CatalogRepository
import com.joystream.app.data.providers.MediaProvider
import com.joystream.app.data.providers.dramachi.DramachiProvider
import com.joystream.app.data.providers.livetv.LiveTvRepository
import com.joystream.app.data.providers.moviebox.MovieBoxClient
import com.joystream.app.data.providers.moviebox.MovieBoxProvider
import com.joystream.app.data.providers.moviebox.MovieBoxSessionStore
import com.joystream.app.data.settings.SettingsRepository
import com.joystream.app.data.update.UpdateRepository

/**
 * Hand-rolled dependency container. Small enough that a DI framework would only
 * add build time — every dependency is created once per process.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val settings = SettingsRepository(appContext)
    val libraryStore = LibraryStore(appContext)
    val liveTv = LiveTvRepository(appContext, settings)
    val updates = UpdateRepository(appContext, settings)
    val updateController = UpdateController(updates, settings)

    private val sessionStore = MovieBoxSessionStore(appContext)
    private val movieBoxClient = MovieBoxClient(
        okHttp = Http.client,
        sessionStore = sessionStore,
        hostOverride = { settings.current.customApiHost },
    )

    val movieBox: MovieBoxProvider = MovieBoxProvider(movieBoxClient)

    val providers: List<MediaProvider> = listOf(
        movieBox,
        DramachiProvider(Http.client),
    )

    val catalog = CatalogRepository(providers, settings)

    fun downloadManager(): DownloadManager = DownloadManager
}
