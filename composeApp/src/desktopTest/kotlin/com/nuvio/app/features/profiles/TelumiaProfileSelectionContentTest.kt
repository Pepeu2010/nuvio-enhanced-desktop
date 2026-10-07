package com.nuvio.app.features.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Production selection content and callbacks; no account, PIN service or backdrop availability claim. */
@OptIn(ExperimentalTestApi::class)
class TelumiaProfileSelectionContentTest {
    private val profiles = (1..6).map { index ->
        NuvioProfile(id="selection-fixture-$index",userId="fixture",profileIndex=index,
            name=if(index==6) "Perfil com nome longo" else "Perfil $index",
            avatarColorHex=listOf("#E8BE72","#DB927A","#90BAA7")[index%3],pinEnabled=index==3)
    }
    @Test fun laptop() = viewport(1366,768)
    @Test fun fullHd() = viewport(1920,1080)
    @Test fun desktop1440p() = viewport(2560,1440)
    @Test fun desktop4k() = viewport(3840,2160)

    private fun viewport(width:Int,height:Int) = runDesktopComposeUiTest(width=width,height=height) {
        val editing=mutableStateOf(false)
        var selected=0
        var highlighted=0
        setContent {
            NuvioTheme(navigationMotion=NavigationMotion.OFF) {
                Box(Modifier.fillMaxSize().background(Color(0xFF080D16))) {
                    ProfileSelectionContent(profiles,editing.value,true,{editing.value=!editing.value},{},
                        {selected=it.profileIndex},{profile,focused->if(focused)highlighted=profile.profileIndex})
                }
            }
        }
        for(index in 1..6) {
            onNodeWithTag("profile-card-$index").assertIsDisplayed()
            val bounds=onNodeWithTag("profile-card-$index").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left>=0 && bounds.top>=0 && bounds.right<=width && bounds.bottom<=height,
                "Profile $index must fit at $width x $height")
        }
        onNodeWithTag("profile-card-1").performClick()
        runOnIdle { assertEquals(1,selected) }
        onNodeWithTag("profile-card-3").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithTag("profile-card-3").performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(3,selected);assertEquals(3,highlighted) }
        val directory=File("build/reports/telumia-profile-selection").apply { mkdirs() }
        ImageIO.write(onNodeWithTag("profile-selection").captureToImage().toAwtImage(),"png",
            File(directory,"selection-${width}x$height.png"))
        onNodeWithTag("profile-manage").performClick()
        runOnIdle { assertTrue(editing.value) }
        onNodeWithTag("profile-add").assertDoesNotExist() // actual six-profile limit
    }

    @Test fun emptyAndLoadingStatesKeepCreationAndManagementHonest() = runDesktopComposeUiTest(width=900,height=600) {
        val loaded=mutableStateOf(false)
        var created=0
        setContent {
            NuvioTheme(navigationMotion=NavigationMotion.OFF) {
                ProfileSelectionContent(emptyList(),false,true,{}, {created++},{},isLoaded=loaded.value)
            }
        }
        onNodeWithTag("profile-loading").assertIsDisplayed()
        onNodeWithTag("profile-manage").assertIsNotEnabled()
        onNodeWithTag("profile-add").assertDoesNotExist()
        runOnIdle { loaded.value=true }
        onNodeWithTag("profile-loading").assertDoesNotExist()
        onNodeWithTag("profile-add").performClick()
        runOnIdle { assertEquals(1,created) }
    }

    @Test fun narrowLayoutScrollsToEveryProfileAndAddsOnlyWhenAllowed() = runDesktopComposeUiTest(width=360,height=640) {
        var selected=0
        var created=0
        setContent {
            NuvioTheme(navigationMotion=NavigationMotion.OFF) {
                ProfileSelectionContent(profiles.take(3),true,true,{}, {created++},{selected=it.profileIndex})
            }
        }
        onNodeWithTag("profile-card-3").performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(3,selected) }
        onNodeWithTag("profile-add").performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1,created) }
    }
}
