@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nuvio.app.features.settings

import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.storage.*
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun PlatformCacheSettingsRows(isTablet: Boolean) {
    var settings by remember { mutableStateOf(DesktopMediaCache.loadSettings()) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DesktopCacheSettingsRows(settings, DesktopMediaCache.activeBudget, isTablet, saving, saveFailed) { next ->
        if (!saving) {
            saving = true
            scope.launch {
                val success = withContext(Dispatchers.IO) { runCatching { DesktopMediaCache.saveSettings(next) }.isSuccess }
                if (success) settings = next
                saveFailed = !success
                saving = false
            }
        }
    }
}

@Composable
internal fun DesktopCacheSettingsRows(settings: MediaCacheSettings, activeBudget: MediaCacheBudget,
    isTablet: Boolean, saving: Boolean = false, saveFailed: Boolean = false, onSave: (MediaCacheSettings) -> Unit) {
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_cache_auto),
        description = stringResource(Res.string.settings_cache_auto_description),
        checked = settings.mode == MediaCacheMode.AUTO,
        enabled = !saving,
        isTablet = isTablet,
        onCheckedChange = { onSave(settings.copy(mode = if (it) MediaCacheMode.AUTO else MediaCacheMode.MANUAL)) },
    )
    if (settings.mode == MediaCacheMode.MANUAL) {
        SettingsChipRow(
            title = stringResource(Res.string.settings_cache_limit),
            description = stringResource(Res.string.settings_cache_limit_description),
            isTablet = isTablet,
        ) {
            listOf(256L, 512L, 1024L, 2048L, 4096L, 8192L, 16384L).forEach { mib ->
                FilterChip(selected = (settings.manualBytes ?: MediaCachePlatform.DESKTOP.initialBytes) == mib * MIB, enabled = !saving,
                    onClick = { onSave(settings.copy(manualBytes = mib * MIB)) },
                    label = { Text("$mib MiB") })
            }
        }
    }
    Column(Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp)) {
        Text(stringResource(Res.string.settings_cache_active_budget, activeBudget.totalBytes / MIB),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(Res.string.settings_cache_restart), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (saving) Text(stringResource(Res.string.settings_cache_saving), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (saveFailed) Text(stringResource(Res.string.settings_cache_save_error), color = MaterialTheme.colorScheme.error)
    }
}
