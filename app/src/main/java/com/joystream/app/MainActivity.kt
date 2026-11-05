package com.joystream.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.joystream.app.core.AppContainer
import com.joystream.app.ui.nav.JoyStreamNav
import com.joystream.app.ui.theme.JoyStreamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as JoyStreamApp).container

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            JoyStreamTheme {
                JoyStreamNav(container = container)
            }
        }

        requestNotificationPermission()
        handlePlaylistIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePlaylistIntent(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Supports opening .m3u playlists with JoyStream from a browser or file manager. */
    private fun handlePlaylistIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    when (uri.scheme) {
                        "content", "file" -> contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }

                        "http", "https" -> {
                            container.settings.setLiveTvUrl(uri.toString())
                            null
                        }

                        else -> null
                    }
                }.getOrNull()
            }

            if (content != null) {
                val imported = container.liveTv.importContent(content)
                Toast.makeText(
                    this@MainActivity,
                    if (imported) "Playlist imported" else "That file is not an M3U playlist",
                    Toast.LENGTH_SHORT,
                ).show()
            } else if (uri.scheme == "http" || uri.scheme == "https") {
                container.liveTv.load(forceRefresh = true)
                Toast.makeText(this@MainActivity, "Playlist saved", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
