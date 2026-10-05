package com.nuvio.app.features.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.CinematicMovieActions
import com.nuvio.app.features.details.components.CinematicSynopsis
import com.nuvio.app.features.details.components.DesktopDetailHero
import org.junit.Rule
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.hero_add_to_library
import nuvio.composeapp.generated.resources.telumia_read_synopsis
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CinematicMovieDetailsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun movieActionsAreVisibleAndRouteKeyboardAndLongPressToExistingCallbacks() {
        var play = 0; var save = 0; var lists = 0; var trailer = 0
        var saveLabel = ""
        compose.setContent {
            NuvioTheme(navigationMotion = NavigationMotion.OFF) {
                saveLabel = stringResource(Res.string.hero_add_to_library)
                CinematicMovieActions("Continuar", true, false, false,
                    { play++ }, null, { save++ }, { lists++ }, {}, { trailer++ })
            }
        }
        compose.onNodeWithText(saveLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText(saveLabel).performTouchInput { longClick() }
        compose.onNodeWithText("Trailer").assertIsDisplayed().performClick()
        compose.onNodeWithText("Continuar").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithText("Continuar").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals(1, play); assertEquals(1, save); assertEquals(1, lists); assertEquals(1, trailer)
        }
    }

    @Test fun missingTrailerIsHiddenAndUnavailablePlaybackCannotBeActivated() {
        var play = 0
        compose.setContent {
            NuvioTheme(navigationMotion = NavigationMotion.OFF) {
                CinematicMovieActions("Indisponível", false, false, false,
                    { play++ }, null, {}, null, {}, null)
            }
        }
        compose.onNodeWithText("Trailer").assertDoesNotExist()
        compose.onNodeWithText("Indisponível").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, play) }
    }

    @Test fun fullSynopsisIsReadableAndDoesNotLeakAcrossMovieChanges() {
        val movie = mutableStateOf("Primeiro filme")
        val description = "Uma descrição longa que não deve desaparecer quando a área do hero for compacta. ".repeat(40)
        var readLabel = ""
        compose.setContent {
            NuvioTheme(navigationMotion = NavigationMotion.OFF) {
                readLabel = stringResource(Res.string.telumia_read_synopsis)
                CinematicSynopsis(movie.value, description, 768.dp, Modifier.width(320.dp))
            }
        }
        compose.onNodeWithText(readLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText("Primeiro filme").assertIsDisplayed()
        compose.onNode(hasText(description) and hasScrollAction()).assertIsDisplayed()
        compose.runOnIdle { movie.value = "Segundo filme" }
        compose.onNodeWithText("Primeiro filme").assertDoesNotExist()
        compose.onNodeWithText("Segundo filme").assertDoesNotExist()
    }

}
