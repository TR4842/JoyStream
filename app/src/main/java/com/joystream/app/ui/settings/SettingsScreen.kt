package com.joystream.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joystream.app.BuildConfig
import com.joystream.app.core.AppContainer
import com.joystream.app.data.providers.MediaProvider
import com.joystream.app.data.settings.Settings
import com.joystream.app.data.update.InstallResult
import com.joystream.app.ui.theme.JoyGreen
import com.joystream.app.ui.theme.JoyMuted
import com.joystream.app.ui.theme.JoyPurple
import com.joystream.app.ui.theme.JoySurfaceHigh
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val updateState by container.updateController.state.collectAsStateWithLifecycle()
    val snapshot by container.libraryStore.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var liveTvDraft by remember(settings.liveTvUrl) { mutableStateOf(settings.liveTvUrl) }
    var apiHostDraft by remember(settings.customApiHost) { mutableStateOf(settings.customApiHost) }
    var probeRunning by remember { mutableStateOf(false) }
    var probeResults by remember { mutableStateOf<List<MediaProvider.HostCheck>?>(null) }
    var probeError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Column(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelSmall,
                    color = JoyMuted,
                )
            }
        }

        // ------------------------------------------------------------ updates
        item { SectionTitle("Updates") }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = JoySurfaceHigh.copy(alpha = 0.55f)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    val update = updateState.available
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (update == null) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.SystemUpdate
                            },
                            contentDescription = null,
                            tint = if (update == null) JoyGreen else JoyPurple,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    updateState.checking -> "Checking for updates…"
                                    update != null -> "Update available · v${update.versionName}"
                                    else -> "You're on the latest build"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = if (update != null) {
                                    "Published ${update.publishedAt?.take(10) ?: "recently"}"
                                } else {
                                    "JoyStream rebuilds automatically when the upstream MovieBox-Tui repo changes."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = JoyMuted,
                            )
                        }
                    }

                    if (updateState.progress >= 0f) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { updateState.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    }

                    if (update != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val file = updateState.downloadedFile
                                    if (file == null) {
                                        container.updateController.download()
                                    } else {
                                        when (val result = container.updateController.install()) {
                                            InstallResult.NeedsPermission -> runCatching {
                                                context.startActivity(
                                                    container.updateController.installPermissionIntent(),
                                                )
                                            }

                                            is InstallResult.Failed -> runCatching {
                                                context.startActivity(
                                                    container.updateController
                                                        .externalInstallerIntent(file),
                                                )
                                            }

                                            InstallResult.Started -> Unit
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (updateState.downloadedFile != null) "Install update"
                                    else "Download update",
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    if (update.releaseUrl.isNotBlank()) {
                                        openUrl(context, update.releaseUrl)
                                    }
                                },
                            ) { Text("Release notes") }
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { container.updateController.refresh() },
                            enabled = !updateState.checking,
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Check now")
                        }
                    }

                    updateState.error?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item {
            updateState.upstream?.let { upstream ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = JoySurfaceHigh.copy(alpha = 0.4f)),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Upstream provider engine", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Built against ${upstream.builtShortSha} · upstream head ${upstream.shortSha}",
                            style = MaterialTheme.typography.bodySmall,
                            color = JoyMuted,
                        )
                        upstream.date?.let {
                            Text(
                                text = "Upstream commit date: ${it.take(10)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = JoyMuted,
                            )
                        }
                        upstream.message?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = JoyMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (upstream.isNewerThanBuild) {
                                "Upstream has new commits — an automatic rebuild is on the way. Check for updates in a few minutes."
                            } else {
                                "This build already includes the newest upstream changes."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (upstream.isNewerThanBuild) JoyPurple else JoyGreen,
                        )
                    }
                }
            }
        }

        item {
            SwitchRow(
                title = "Check for updates automatically",
                subtitle = "Runs at launch and picks up builds generated after upstream changes.",
                checked = settings.autoCheckUpdates,
                onCheckedChange = { value ->
                    container.settings.update { it.copy(autoCheckUpdates = value) }
                },
            )
        }
        item {
            SwitchRow(
                title = "Download updates in the background",
                subtitle = "Fetches the APK automatically; you still confirm the install.",
                checked = settings.autoDownloadUpdates,
                onCheckedChange = { value ->
                    container.settings.update { it.copy(autoDownloadUpdates = value) }
                },
            )
        }

        // ---------------------------------------------------------- providers
        item { SectionTitle("Providers") }
        container.providers.forEach { provider ->
            item(key = "provider-${provider.id}") {
                SwitchRow(
                    title = provider.displayName,
                    subtitle = provider.description,
                    checked = settings.isProviderEnabled(provider.id),
                    onCheckedChange = { container.settings.toggleProvider(provider.id) },
                )
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = JoySurfaceHigh.copy(alpha = 0.55f)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Wifi, contentDescription = null, tint = JoyPurple)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Connection test", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Checks every MovieBox mirror and reports what each one returns.",
                                style = MaterialTheme.typography.bodySmall,
                                color = JoyMuted,
                            )
                        }
                    }

                    val results = probeResults
                    if (results != null) {
                        Spacer(Modifier.height(10.dp))
                        if (results.isEmpty()) {
                            Text(
                                text = probeError ?: "No mirrors reported a result.",
                                style = MaterialTheme.typography.bodySmall,
                                color = JoyMuted,
                            )
                        }
                        results.forEach { check ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = check.host,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (check.ok) JoyGreen else JoyMuted,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = check.summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (check.ok) JoyGreen else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (results.none { it.ok }) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "No mirror accepted the request. If this happens on every network, " +
                                    "the provider changed its API — an app update from the releases page " +
                                    "will carry the fix.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiHostDraft,
                        onValueChange = { apiHostDraft = it },
                        label = { Text("Custom API host (optional)") },
                        placeholder = { Text("https://api6.aoneroom.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Only needed when your network blocks the built-in mirrors. " +
                            "Leave empty to use them automatically.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JoyMuted,
                    )

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { container.settings.setCustomApiHost(apiHostDraft) },
                        ) { Text("Save host") }
                        if (settings.customApiHost.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    apiHostDraft = ""
                                    container.settings.setCustomApiHost("")
                                },
                            ) { Text("Reset") }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        enabled = !probeRunning,
                        onClick = {
                            probeRunning = true
                            probeError = null
                            scope.launch {
                                val outcome = runCatching {
                                    container.movieBox.probeConnection()
                                }
                                probeResults = outcome.getOrDefault(emptyList())
                                outcome.exceptionOrNull()?.let { probeError = it.message }
                                probeRunning = false
                            }
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (probeRunning) "Testing…" else "Test connection")
                    }
                }
            }
        }

        // ----------------------------------------------------------- playback
        item { SectionTitle("Playback") }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Preferred quality", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Caps the adaptive stream and picks the closest direct source.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JoyMuted,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.QUALITY_OPTIONS.forEach { quality ->
                        val label = if (quality == "auto") "Auto" else "${quality}p"
                        Surface(
                            color = if (settings.preferredQuality == quality) {
                                JoyPurple.copy(alpha = 0.3f)
                            } else {
                                JoySurfaceHigh
                            },
                            shape = CircleShape,
                            modifier = Modifier.clickable {
                                container.settings.setQuality(quality)
                            },
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ live TV
        item { SectionTitle("Live TV") }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = liveTvDraft,
                    onValueChange = { liveTvDraft = it },
                    label = { Text("M3U playlist URL") },
                    placeholder = { Text("https://example.com/playlist.m3u") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            container.settings.setLiveTvUrl(liveTvDraft)
                            scope.launch { container.liveTv.load(forceRefresh = true) }
                        },
                    ) { Text("Save & load") }
                    OutlinedButton(
                        onClick = { scope.launch { container.liveTv.load(forceRefresh = true) } },
                    ) { Text("Reload") }
                }
            }
        }

        // --------------------------------------------------------- data saver
        item { SectionTitle("Downloads & storage") }
        item {
            SwitchRow(
                title = "Only download on Wi-Fi",
                subtitle = "Pauses the download queue on mobile data.",
                checked = settings.wifiOnlyDownloads,
                onCheckedChange = { value ->
                    container.settings.update { it.copy(wifiOnlyDownloads = value) }
                },
            )
        }
        item {
            ClickRow(
                title = "Clear watch history",
                subtitle = "${snapshot.history.size} entries stored on this device",
                actionLabel = "Clear",
                onClick = { scope.launch { container.libraryStore.clearHistory() } },
            )
        }
        item {
            ClickRow(
                title = "Clear search history",
                subtitle = "${snapshot.searchHistory.size} recent searches",
                actionLabel = "Clear",
                onClick = { scope.launch { container.libraryStore.clearSearchHistory() } },
            )
        }

        // -------------------------------------------------------------- about
        item { SectionTitle("About") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "JoyStream is a native Android client for the MovieBox-Tui provider engine. It does not host or store media; it plays publicly available streams. Keep within your local laws.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JoyMuted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            openUrl(context, "https://github.com/${BuildConfig.APP_REPO}/releases")
                        },
                    ) {
                        Icon(
                            Icons.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Releases")
                    }
                    OutlinedButton(
                        onClick = {
                            openUrl(context, "https://github.com/${BuildConfig.UPSTREAM_REPO}")
                        },
                    ) {
                        Icon(
                            Icons.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Upstream")
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { openUrl(context, "https://github.com/${BuildConfig.UPSTREAM_REPO}/blob/main/LICENSE-MIT") }) {
                    Text("Licenses (MIT / Apache-2.0)")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(18.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = JoyPurple,
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = JoyMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ClickRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = JoyMuted)
        }
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
