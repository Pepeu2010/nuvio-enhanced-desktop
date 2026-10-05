package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.telumia_read_synopsis
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CinematicSynopsis(title: String, text: String, viewportHeight: Dp, modifier: Modifier = Modifier) {
    var showFull by remember(title, text) { mutableStateOf(false) }
    var truncated by remember(title, text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { truncated = it.hasVisualOverflow },
        )
        if (truncated) {
            TextButton(onClick = { showFull = true }) {
                Text(stringResource(Res.string.telumia_read_synopsis))
            }
        }
    }
    if (showFull) {
        DialogSurface(onDismissRequest = { showFull = false }, title = title) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.heightIn(max = (viewportHeight * 0.6f).coerceAtLeast(160.dp))
                        .verticalScroll(rememberScrollState()),
                )
            }
            DialogButtons {
                DialogButton(stringResource(Res.string.action_close), onClick = { showFull = false })
            }
        }
    }
}
