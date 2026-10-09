package com.nuvio.app.features.player.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DesktopSceneBookmarksPanel(
    state: DesktopSceneBookmarkPanelState,
    positionMs: Long,
    canSave: Boolean,
    onSave: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onJump: (String, Boolean) -> Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultName = stringResource(Res.string.scene_bookmarks_default_name)
    var name by remember(state.scope) { mutableStateOf(defaultName) }
    var editingId by remember(state.scope) { mutableStateOf<String?>(null) }
    var pendingJump by remember(state.scope) { mutableStateOf<String?>(null) }
    var pendingRename by remember(state.scope) { mutableStateOf<Pair<String, String>?>(null) }
    var jumpRejected by remember(state.scope) { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val writable = state.scope != null && !state.loading && !state.busy && !state.failed
    Surface(Modifier.fillMaxSize().testTag("scene-bookmarks-panel").onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false
    }, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.scene_bookmarks_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.scene_bookmarks_scope_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pendingJump != null) {
                Text(stringResource(Res.string.scene_bookmarks_confirm_notice))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { pendingJump?.let {
                        jumpRejected = !onJump(it, true)
                        if (!jumpRejected) pendingJump = null
                    } }, enabled = writable,
                        modifier = Modifier.focusRequester(confirmFocus).testTag("scene-bookmark-confirm")) { Text(stringResource(Res.string.scene_bookmarks_confirm)) }
                    OutlinedButton(onClick = { pendingJump = null; jumpRejected = false }) { Text(stringResource(Res.string.scene_bookmarks_cancel_jump)) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it.filterNot(Char::isISOControl).take(128) }, enabled = writable,
                        label = { Text(stringResource(Res.string.scene_bookmarks_name)) }, singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(nameFocus).testTag("scene-bookmark-name"))
                    Button(onClick = {
                        val id = editingId
                        if (id == null) onSave(name) else { pendingRename = id to name.trim(); onRename(id, name) }
                    }, enabled = writable && name.isNotBlank() && (editingId != null || canSave),
                        modifier = Modifier.testTag("scene-bookmark-save")) {
                        Text(if (editingId == null) stringResource(Res.string.scene_bookmarks_save, bookmarkTime(positionMs))
                            else stringResource(Res.string.scene_bookmarks_rename))
                    }
                }
            }
            if (jumpRejected) Text(stringResource(Res.string.scene_bookmarks_seek_unavailable),
                modifier = Modifier.testTag("scene-bookmark-seek-error"), color = MaterialTheme.colorScheme.error)
            when {
                state.loading || state.busy -> Text(stringResource(Res.string.scene_bookmarks_loading))
                state.failed -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.scene_bookmarks_error), modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("scene-bookmark-retry")) { Text(stringResource(Res.string.scene_bookmarks_retry)) }
                }
                state.scope == null -> Text(stringResource(Res.string.scene_bookmarks_unavailable))
                state.items.isEmpty() -> Text(stringResource(Res.string.scene_bookmarks_empty))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("scene-bookmark-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            jumpRejected = false
                            if (item.editionKey != state.scope?.editionKey) pendingJump = item.id
                            else jumpRejected = !onJump(item.id, false)
                        }, enabled = writable && pendingJump == null, modifier = Modifier.weight(1f).testTag("scene-bookmark-jump-${item.id}")) {
                            Column {
                                Text("${bookmarkTime(item.positionMs)} · ${item.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item.editionKey != state.scope?.editionKey) Text(stringResource(Res.string.scene_bookmarks_other_source),
                                    style = MaterialTheme.typography.labelSmall)
                                Text(remember(item.createdAtMs) { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.createdAtMs)) },
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(onClick = { editingId = item.id; name = item.name; nameFocus.requestFocus() },
                            enabled = writable && pendingJump == null, modifier = Modifier.testTag("scene-bookmark-rename-${item.id}")) {
                            Text(stringResource(Res.string.scene_bookmarks_rename))
                        }
                        TextButton(onClick = { onRemove(item.id) }, enabled = writable && pendingJump == null,
                            modifier = Modifier.testTag("scene-bookmark-remove-${item.id}")) { Text(stringResource(Res.string.scene_bookmarks_remove)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editingId != null) OutlinedButton(onClick = { editingId = null; pendingRename = null; name = defaultName }) { Text(stringResource(Res.string.scene_bookmarks_cancel)) }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus).testTag("scene-bookmark-close")) {
                    Text(stringResource(Res.string.scene_bookmarks_close))
                }
            }
        }
    }
    LaunchedEffect(state.items, state.busy, state.failed, pendingRename) {
        val pending = pendingRename
        if (pending != null && !state.busy && !state.failed &&
            state.items.any { it.id == pending.first && it.name == pending.second }) {
            editingId = null
            pendingRename = null
            name = defaultName
        }
    }
    LaunchedEffect(state.scope, writable, pendingJump) {
        if (writable && pendingJump != null) confirmFocus.requestFocus()
        else if (writable) nameFocus.requestFocus() else closeFocus.requestFocus()
    }
}

internal fun bookmarkTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
}
