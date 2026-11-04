package com.joystream.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.joystream.app.core.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JoyStreamApp : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()

        scope.launch {
            container.libraryStore.load()
            container.updateController.checkOnStartup()
            runCatching { container.liveTv.load() }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_UPDATES) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_UPDATES,
                    getString(R.string.update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = getString(R.string.update_channel_description)
                    setShowBadge(true)
                },
            )
        }
    }

    private companion object {
        const val CHANNEL_UPDATES = "updates"
    }
}
