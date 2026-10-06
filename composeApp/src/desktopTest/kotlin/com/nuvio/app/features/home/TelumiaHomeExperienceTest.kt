package com.nuvio.app.features.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.components.HomeHeroSection
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.home_view_details
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TelumiaHomeExperienceTest {
    private val movie = MetaPreview(id = "fixture:cinema:br", type = "movie",
        name = "Uma jornada por histórias brasileiras", releaseInfo = "2026", imdbRating = "8.1",
        genres = listOf("Drama", "Aventura"),
        description = "Uma viagem entre cidades, paisagens e histórias que mudam a vida de uma família. ".repeat(8))

    @Test fun laptop1366() = viewport(1366, 768)
    @Test fun fullHd1920() = viewport(1920, 1080)
    @Test fun desktop1440p() = viewport(2560, 1440)
    @Test fun desktop4k() = viewport(3840, 2160)

    private fun viewport(width: Int, height: Int) = runDesktopComposeUiTest(width = width, height = height) {
        var actionLabel = ""
        var selected: MetaPreview? = null
        setContent {
            NuvioTheme(navigationMotion = NavigationMotion.OFF) {
                actionLabel = stringResource(Res.string.home_view_details)
                Box(Modifier.fillMaxSize().testTag("home-viewport")) {
                    HomeHeroSection(listOf(movie), viewportHeight = height.dp, onItemClick = { selected = it })
                }
            }
        }
        onNodeWithText(movie.name).assertIsDisplayed()
        assertTrue(onNodeWithText(movie.description!!).fetchSemanticsNode().boundsInRoot.width <= 721f,
            "Synopsis must retain a readable measure on wide viewports")
        onNodeWithText(actionLabel).assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText(actionLabel).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(movie, selected) }
        val image = onNodeWithTag("home-viewport").captureToImage().toAwtImage()
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        ImageIO.write(image, "png", File(File("build/reports/telumia-home").apply { mkdirs() }, "home-$width.png"))
    }

    @Test fun keyboardSelectsSourceItemAndFocusStopsAutomaticCycling() = runDesktopComposeUiTest(width = 1366, height = 768) {
        val series = movie.copy(id = "fixture:series:br", type = "series", name = "Histórias de uma cidade")
        var selected: MetaPreview? = null
        var actionLabel = ""
        setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            actionLabel = stringResource(Res.string.home_view_details)
            HomeHeroSection(listOf(movie, series), viewportHeight = 768.dp, onItemClick = { selected = it })
        } }
        val selector = onNode(hasClickAction() and hasContentDescription(series.name))
        selector.performSemanticsAction(SemanticsActions.RequestFocus)
        selector.performKeyInput { pressKey(Key.Enter) }
        selector.assertIsSelected()
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(10_000)
        onNodeWithText(series.name).assertIsDisplayed()
        selector.assertIsSelected()
        mainClock.autoAdvance = true
        onNodeWithText(actionLabel).performClick()
        runOnIdle { assertEquals(series, selected) }
    }

    @Test fun missingMetadataAndActionsDoNotCreateInventedBadgesOrButtons() = runDesktopComposeUiTest(width = 1366, height = 768) {
        val item = mutableStateOf(MetaPreview("fixture:unknown", "series", "Sem informações adicionais", imdbRating = "NaN"))
        setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            HomeHeroSection(listOf(item.value), viewportHeight = 768.dp)
        } }
        onNodeWithText("IMDb", substring = true).assertDoesNotExist()
        onAllNodes(hasClickAction()).assertCountEquals(1) // The supported native fullscreen action only.
        runOnIdle { item.value = item.value.copy(imdbRating = "20.0") }
        onNodeWithText("IMDb", substring = true).assertDoesNotExist()
    }
}
