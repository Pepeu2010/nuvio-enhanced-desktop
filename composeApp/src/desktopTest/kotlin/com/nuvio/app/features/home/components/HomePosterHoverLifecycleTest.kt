package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.core.ui.PosterZoomOverlayCoordinator
import com.nuvio.app.features.home.MetaPreview
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real hover/popup lifecycle with a controlled suspended resolver; no trailer availability/playback claim. */
@OptIn(ExperimentalTestApi::class)
class HomePosterHoverLifecycleTest {
    @Test fun leavingTheRealPopupCancelsResolutionAndReleasesItsSlotWithMotionOff() = runDesktopComposeUiTest(width=1366,height=768) {
        val started = AtomicBoolean()
        val cleanedUp = AtomicBoolean()
        val settings = PosterCardStyleUiState(hoverPreviewEnabled=true, hoverPreviewOpenDelayMillis=500,
            hoverPreviewTrailerEnabled=true, hoverPreviewTrailerSoundEnabled=false)
        runOnIdle { PosterZoomOverlayCoordinator.hide() }
        setContent {
            NuvioTheme(navigationMotion=NavigationMotion.OFF) {
                Box(Modifier.padding(64.dp)) {
                    HomePosterHoverPreview(MetaPreview("fixture:hover", "movie", "Uma história para descobrir"),
                        isWatched=false, onClick={}, onLongClick=null, previewSettings=settings,
                        // Controlled supported-platform resolver fixture. The Windows surface is
                        // currently disabled; this does not claim Windows trailer playback.
                        trailerPreviewSupported=true,
                        trailerResolver={
                            started.set(true)
                            try { awaitCancellation() } finally { cleanedUp.set(true) }
                        }) { hover ->
                        Box(hover.testTag("hover-anchor").size(200.dp,300.dp).background(Color(0xFF18283A))) {
                            Text("Card local")
                        }
                    }
                }
            }
        }
        onNodeWithTag("hover-anchor").performMouseInput { enter(center) }
        waitForIdle()
        waitUntil(timeoutMillis=4_000) { onAllNodesWithTag("home-hover-preview").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("home-hover-preview").performMouseInput { enter(center) }
        waitForIdle()
        waitUntil(timeoutMillis=8_000) { started.get() }
        onNodeWithTag("home-hover-preview").assertIsDisplayed()
        val directory=File("build/reports/telumia-hover-preview").apply { mkdirs() }
        ImageIO.write(onNodeWithTag("home-hover-preview").captureToImage().toAwtImage(), "png", File(directory,"static-pending-preview.png"))
        onNodeWithTag("home-hover-preview").performMouseInput { exit() }
        waitForIdle()
        waitUntil(timeoutMillis=4_000) { cleanedUp.get() && homePosterPreviewOwnership.active.value == null }
        onNodeWithTag("home-hover-preview").assertDoesNotExist()
        runOnIdle { assertTrue(cleanedUp.get()); assertNull(homePosterPreviewOwnership.active.value) }
    }
}
