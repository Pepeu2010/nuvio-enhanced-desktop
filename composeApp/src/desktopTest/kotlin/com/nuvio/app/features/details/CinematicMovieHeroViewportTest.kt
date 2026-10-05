package com.nuvio.app.features.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.DesktopDetailHero
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.hero_add_to_library
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CinematicMovieHeroViewportTest {
    @Test fun laptop1366() = verifyViewport(1366,768)
    @Test fun fullHd1920() = verifyViewport(1920,1080)
    @Test fun desktop1440p() = verifyViewport(2560,1440)
    @Test fun desktop4k() = verifyViewport(3840,2160)

    private fun verifyViewport(width:Int, height:Int) = runDesktopComposeUiTest(width=width, height=height) {
        var saveLabel = ""
        val meta = MetaDetails(id="fixture:movie", type="movie", name="Uma jornada pelo Brasil",
            releaseInfo="2026", runtime="124 min", ageRating="12", genres=listOf("Drama", "Aventura"),
            description="Uma viagem entre cidades, paisagens e histórias que mudam a vida de uma família. ".repeat(12))
        setContent {
            NuvioTheme(navigationMotion = NavigationMotion.OFF) {
                saveLabel = stringResource(Res.string.hero_add_to_library)
                Box(Modifier.fillMaxSize().testTag("cinematic-film-hero")) {
                    DesktopDetailHero(meta=meta, viewportHeight=height.dp, showOverallRatings=false,
                        isMdbListActive=false, playButtonLabel="Assistir", isPrimaryPlayEnabled=true,
                        isSaved=false, isWatched=false, onHeightChanged={}, heroTrailerSourceUrl=null,
                        heroTrailerReady=false, heroTrailerMuted=true, onHeroTrailerMuteToggle={},
                        onPlayClick={}, onPlayLongClick=null, onShuffleClick=null, shuffleEnabled=false,
                        onWatchedClick={}, onSaveClick={}, onSaveLongClick=null, onTrailerClick={})
                }
            }
        }
        onNodeWithText("Assistir").assertIsDisplayed()
        onNodeWithText(saveLabel).assertIsDisplayed()
        onNodeWithText("Trailer").assertIsDisplayed()
        val directory=File("build/reports/cinematic-details").apply { mkdirs() }
        ImageIO.write(onNodeWithTag("cinematic-film-hero").captureToImage().toAwtImage(),
            "png", File(directory,"movie-hero-$width.png"))
    }
}
