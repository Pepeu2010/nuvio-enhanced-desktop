package com.nuvio.app.features.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.metadata.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TimedMetadataTimelineUiTest {
    private val marker = PlayerTimedMarker("intro", TimedMetadataKind.INTRO, .1f, .2f, "Abertura", "introdb")

    @Test fun actualSeekSemanticsStayFunctionalWithTimedMarkersAndClearOnEpisodeChange() = runDesktopComposeUiTest(width = 1366, height = 768) {
        val markers = mutableStateOf(listOf(marker))
        var preview = 0L
        var committed = 0L
        setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            Box(Modifier.fillMaxSize().padding(48.dp).testTag("timeline-viewport")) {
                PlayerTimeline(PlayerPlaybackSnapshot(durationMs = 100_000, positionMs = 20_000), 20_000,
                    onScrubChange = { preview = it }, onScrubFinished = { committed = it }, timedMarkers = markers.value)
            }
        } }
        val seek = onNode(hasContentDescription("Abertura", substring = true))
        seek.assertIsDisplayed().performSemanticsAction(SemanticsActions.SetProgress) { it(50_000f) }
        runOnIdle { assertEquals(50_000L, preview); assertEquals(50_000L, committed) }
        val image = onNodeWithTag("timeline-viewport").captureToImage().toAwtImage()
        val file = File("build/telumia-timed-ui/desktop-timeline.png").also { it.parentFile.mkdirs() }
        ImageIO.write(image, "png", file)
        runOnIdle { markers.value = emptyList() }
        onNode(hasContentDescription("Abertura", substring = true)).assertDoesNotExist()
    }

    @Test fun unknownDurationDoesNotOfferSeekAction() = runDesktopComposeUiTest {
        var calls = 0
        setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            PlayerTimeline(PlayerPlaybackSnapshot(), 0, { calls++ }, { calls++ }, modifier = Modifier.testTag("seek"))
        } }
        onNodeWithTag("seek").assertIsNotEnabled()
        runOnIdle { assertEquals(0, calls) }
    }
}
