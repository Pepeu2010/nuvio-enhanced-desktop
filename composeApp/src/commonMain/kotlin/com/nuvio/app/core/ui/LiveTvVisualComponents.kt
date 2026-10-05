package com.nuvio.app.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Presentation supplied by a real guide adapter; this file contains no channels or schedules. */
internal data class LiveTvProgramPresentation(val key: String, val title: String, val timeLabel: String, val isCurrent: Boolean = false)

@Composable
internal fun LiveTvChannelTile(name: String, number: String?, logoUrl: String?, isSelected: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveTvAction(isSelected, onClick, modifier.width(NuvioTokens.LiveTv.channelWidth)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!logoUrl.isNullOrBlank()) NuvioAsyncImage(model = logoUrl, contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                number?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun LiveTvNowNext(nowTitle: String, nextTitle: String?, nextLabel: String, progress: Float?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(nowTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        progress?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)?.let { fraction ->
            Box(Modifier.fillMaxWidth().height(NuvioTokens.LiveTv.progressHeight)
                .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
        nextTitle?.takeIf(String::isNotBlank)?.let {
            Text("$nextLabel: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun LiveTvProgramCell(program: LiveTvProgramPresentation, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveTvAction(program.isCurrent, onClick, modifier.width(NuvioTokens.LiveTv.programWidth)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(program.timeLabel, style = MaterialTheme.typography.labelSmall)
            Text(program.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Lazy visual row; source loading, time zones, synchronized guide scrolling and playback belong to Phase 6. */
@Composable
internal fun LiveTvGuideRow(channelName: String, channelNumber: String?, logoUrl: String?, selectedChannel: Boolean,
    programs: List<LiveTvProgramPresentation>, onChannelClick: () -> Unit, onProgramClick: (String) -> Unit,
    modifier: Modifier = Modifier, scrollState: LazyListState = rememberLazyListState()) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.LiveTv.itemSpacing), verticalAlignment = Alignment.CenterVertically) {
        LiveTvChannelTile(channelName, channelNumber, logoUrl, selectedChannel, onChannelClick)
        LazyRow(Modifier.weight(1f), state = scrollState,
            contentPadding = PaddingValues(NuvioTokens.LiveTv.focusBorderWidth),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.LiveTv.itemSpacing)) {
            items(programs, key = { it.key }) { program -> LiveTvProgramCell(program, { onProgramClick(program.key) }) }
        }
    }
}

@Composable
private fun LiveTvAction(isSelected: Boolean, onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.heightIn(min = NuvioTokens.LiveTv.rowHeight)
        .onFocusChanged { focused = it.isFocused }.semantics { selected = isSelected },
        shape = RoundedCornerShape(NuvioTokens.LiveTv.cornerRadius),
        color = if (isSelected) colors.primaryContainer else colors.surface,
        contentColor = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
        border = if (focused) BorderStroke(NuvioTokens.LiveTv.focusBorderWidth, colors.primary) else null) {
        Box(Modifier.padding(NuvioTokens.LiveTv.contentPadding), contentAlignment = Alignment.CenterStart) { content() }
    }
}
