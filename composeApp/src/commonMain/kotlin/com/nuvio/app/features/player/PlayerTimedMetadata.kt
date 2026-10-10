package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nuvio.app.features.player.metadata.*
import com.nuvio.app.features.player.skip.SkipInterval
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Projects the already-loaded skip intervals; episode/source changes replace the scoped snapshot. */
@Composable
internal fun rememberPlayerTimedMarkers(scope: TimedMetadataScope, intervals: List<SkipInterval>, durationMs: Long,
    chapters: EmbeddedChapterSnapshot = EmbeddedChapterSnapshot()): List<PlayerTimedMarker> {
    val intro = stringResource(Res.string.player_timeline_intro)
    val recap = stringResource(Res.string.player_timeline_recap)
    val credits = stringResource(Res.string.player_timeline_credits)
    val postCredits = stringResource(Res.string.player_timeline_post_credits)
    return remember(scope, intervals, durationMs, chapters, intro, recap, credits, postCredits) {
        val events = skipTimedMetadata(scope, intervals, durationMs).events +
            embeddedChapterTimedMetadata(scope, chapters, durationMs).events
        TimedMetadataTimeline.create(scope, events, durationMs).toPlayerMarkers(durationMs) { kind -> when (kind) {
            TimedMetadataKind.INTRO -> intro
            TimedMetadataKind.RECAP -> recap
            TimedMetadataKind.CREDITS -> credits
            TimedMetadataKind.POST_CREDITS -> postCredits
            else -> ""
        } }
    }
}
