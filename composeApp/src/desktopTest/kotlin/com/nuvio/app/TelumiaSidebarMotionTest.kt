package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_nav_library
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class TelumiaSidebarMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sidebarKeepsMouseKeyboardAndSelectionInEveryMotionMode() {
        val mode = mutableStateOf(NavigationMotion.FULL)
        val selected = mutableStateOf(AppScreenTab.Home)
        val search = runBlocking { getString(Res.string.compose_nav_search) }
        val library = runBlocking { getString(Res.string.compose_nav_library) }
        compose.setContent {
            NuvioTheme(navigationMotion = mode.value) {
                Box(Modifier.size(224.dp, 600.dp).testTag("sidebar")) {
                    DesktopHoverSidebar(selectedTab = selected.value, onTabSelected = { selected.value = it },
                        onProfileSelected = {}, onAddProfileRequested = {}, sidebarExpanded = true,
                        sidebarWidth = DesktopSidebarExpandedWidth)
                }
            }
        }
        for (motion in NavigationMotion.entries) {
            compose.runOnIdle { mode.value = motion }
            compose.onNodeWithText(search).performClick()
            compose.runOnIdle { assertEquals(AppScreenTab.Search, selected.value) }
            compose.onNodeWithText(library).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithText(library).performKeyInput { pressKey(Key.Enter) }
            compose.runOnIdle { assertEquals(AppScreenTab.Library, selected.value) }
            compose.onNodeWithText(library).assertIsSelected()
            val dir = File("build/reports/telumia-sidebar").apply { mkdirs() }
            ImageIO.write(compose.onNodeWithTag("sidebar").captureToImage().toAwtImage(), "png", File(dir, "${motion.name.lowercase()}.png"))
        }
    }

    @Test fun tabEntrancesRetainStateAndHideInactiveSemantics() {
        val selected = mutableStateOf(AppScreenTab.Home)
        val mode = mutableStateOf(NavigationMotion.FULL)
        val mounts = mutableMapOf<AppScreenTab, Int>()
        compose.setContent {
            NuvioTheme(navigationMotion = mode.value) {
                RootTabHost(selected.value) { tab ->
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        mounts[tab] = (mounts[tab] ?: 0) + 1
                        onDispose {}
                    }
                    Box(Modifier.size(100.dp).testTag(tab.name))
                }
            }
        }
        for (motion in NavigationMotion.entries) {
            compose.runOnIdle { mode.value = motion }
            for (tab in AppScreenTab.entries) {
                compose.runOnIdle { selected.value = tab }
                compose.onNodeWithTag(tab.name).assertIsDisplayed()
                AppScreenTab.entries.filter { it != tab }.forEach { compose.onNodeWithTag(it.name).assertDoesNotExist() }
            }
        }
        compose.runOnIdle { assertEquals(AppScreenTab.entries.associateWith { 1 }, mounts) }
    }
}
