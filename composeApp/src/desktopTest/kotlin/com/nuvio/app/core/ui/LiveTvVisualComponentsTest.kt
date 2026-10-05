package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveTvVisualComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun channelAndProgramUseNativeKeyboardActionsAndRealPresentationKeys() {
        var channelClicks = 0
        var selectedProgram: String? = null
        compose.setContent {
            NuvioTheme(navigationMotion = NavigationMotion.OFF) {
                Column(Modifier.width(900.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    LiveTvGuideRow("Canal de teste", "12", null, true,
                        listOf(LiveTvProgramPresentation("schedule:1", "Programa de teste", "20:00 – 21:00", true)),
                        { channelClicks++ }, { selectedProgram = it })
                    LiveTvNowNext("Programa de teste", "Próximo programa de teste", "A seguir", 0.25f)
                }
            }
        }
        compose.onNodeWithText("Canal de teste").assertIsSelected().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Canal de teste").performKeyInput { pressKey(Key.Enter) }
        compose.onNode(hasClickAction() and hasText("Programa de teste"))
            .assertIsSelected().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(hasClickAction() and hasText("Programa de teste")).performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, channelClicks); assertEquals("schedule:1", selectedProgram) }
        val output = File("build/reports/telumia-live-design").apply { mkdirs() }
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File(output, "live-guide-components.png"))
    }

    @Test fun unknownProgressDoesNotInventAValueAndAccessibleProgressUpdates() {
        val progress = mutableStateOf<Float?>(null)
        compose.setContent { NuvioTheme { LiveTvNowNext("Atual", null, "A seguir", progress.value, Modifier.width(300.dp)) } }
        val range = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        compose.onAllNodes(range).assertCountEquals(0)
        compose.runOnIdle { progress.value = 0.25f }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f))).assertIsDisplayed()
        compose.runOnIdle { progress.value = Float.NaN }
        compose.onAllNodes(range).assertCountEquals(0)
        compose.onNodeWithText("A seguir", substring = true).assertDoesNotExist()
    }

    @Test fun emptyGuideRetainsOnlyTheChannelAction() {
        compose.setContent { NuvioTheme { LiveTvGuideRow("Sem programação", null, null, false, emptyList(), {}, {}, Modifier.width(900.dp)) } }
        compose.onNodeWithText("Sem programação").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }
}
