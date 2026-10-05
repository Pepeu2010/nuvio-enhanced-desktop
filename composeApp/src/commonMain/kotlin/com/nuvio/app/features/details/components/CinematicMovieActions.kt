package com.nuvio.app.features.details.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.secondaryClick
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.hero_add_to_library
import nuvio.composeapp.generated.resources.hero_remove_from_library
import nuvio.composeapp.generated.resources.hero_mark_watched
import nuvio.composeapp.generated.resources.hero_mark_unwatched
import nuvio.composeapp.generated.resources.telumia_watch_trailer
import org.jetbrains.compose.resources.stringResource

/** Existing movie actions, exposed directly without adding a new playback resolver. */
@Composable
internal fun CinematicMovieActions(
    playLabel: String,
    playEnabled: Boolean,
    isSaved: Boolean,
    isWatched: Boolean,
    onPlay: () -> Unit,
    onPlayLongClick: (() -> Unit)?,
    onSave: () -> Unit,
    onSaveLongClick: (() -> Unit)?,
    onWatched: () -> Unit,
    onTrailer: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MovieAction(playLabel, Icons.Default.PlayArrow, onPlay, onPlayLongClick,
            primary = true, enabled = playEnabled)
        MovieAction(
            stringResource(if (isSaved) Res.string.hero_remove_from_library else Res.string.hero_add_to_library),
            if (isSaved) Icons.Default.Check else Icons.Default.Add,
            onSave, onSaveLongClick, selected = isSaved,
        )
        onTrailer?.let {
            MovieAction(stringResource(Res.string.telumia_watch_trailer), Icons.Default.Movie, it)
        }
        MovieAction(
            stringResource(if (isWatched) Res.string.hero_mark_unwatched else Res.string.hero_mark_watched),
            if (isWatched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
            onWatched, selected = isWatched,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MovieAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    primary: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (primary && enabled) colors.onBackground else colors.surfaceVariant.copy(alpha = 0.88f),
        contentColor = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            primary -> colors.background
            selected -> colors.primary
            else -> colors.onSurface
        },
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick)
                .secondaryClick(onLongClick.takeIf { enabled })
                .heightIn(min = 56.dp)
                .padding(horizontal = if (primary) 28.dp else 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
