package com.nuvio.app.features.player.metadata

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.profiles.ProfileAvatarScope
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopSceneBookmarksPanelTest {
    @get:Rule val compose = createComposeRule()
    private val scope = SceneBookmarkScope(ProfileAvatarScope("fixture-account", 2, "stable-profile"),
        "tt12879200", "series", "tt12879200:1:2", SceneBookmarkScope.sourceEdition("br-cut"))

    @Test fun keyboardSaveRenameMouseJumpAndRemoveKeepRealDurableDataInEveryMotionMode() {
        val root = Files.createTempDirectory("telumia-bookmark-panel-")
        val store = SceneBookmarkStore(root)
        val state = mutableStateOf(DesktopSceneBookmarkPanelState(scope, loading = false))
        val mode = mutableStateOf(NavigationMotion.FULL)
        val jumps = mutableListOf<Long>()
        try {
            compose.setContent { NuvioTheme(navigationMotion = mode.value) {
                Box(Modifier.size(740.dp, 600.dp)) {
                    DesktopSceneBookmarksPanel(state.value, 32_180, true,
                        onSave = { state.value = state.value.copy(items = store.save(scope, 32_180, 100_000, it)) },
                        onRename = { id, name -> state.value = state.value.copy(items = store.rename(scope, id, name)) },
                        onRemove = { state.value = state.value.copy(items = store.remove(scope, it)) },
                        onJump = { id, _ -> jumps += store.load(scope).single { it.id == id }.positionMs; true },
                        onRetry = {}, onDismiss = {})
                }
            } }
            for (motion in NavigationMotion.entries) {
                compose.runOnIdle { mode.value = motion }
                compose.onNodeWithTag("scene-bookmark-name").performTextReplacement("Cena para rever 🎬")
                activateByKeyboard("scene-bookmark-save")
                val item = store.load(scope).single()
                assertEquals(32_180L, item.positionMs)
                compose.onNodeWithTag("scene-bookmark-rename-${item.id}").performClick()
                compose.onNodeWithTag("scene-bookmark-name").performTextReplacement("Áudio e ação")
                activateByKeyboard("scene-bookmark-save")
                assertEquals("Áudio e ação", SceneBookmarkStore(root).load(scope).single().name)
                compose.onNodeWithTag("scene-bookmark-jump-${item.id}").performClick()
                assertEquals(32_180L, jumps.last())
                val directory = File("build/reports/telumia-scene-bookmarks-desktop").apply { mkdirs() }
                ImageIO.write(compose.onNodeWithTag("scene-bookmarks-panel").captureToImage().toAwtImage(),
                    "png", File(directory, "${motion.name.lowercase()}.png"))
                compose.onNodeWithTag("scene-bookmark-remove-${item.id}").performClick()
                assertTrue(SceneBookmarkStore(root).load(scope).isEmpty())
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test fun unavailableAndFailedStatesDisableMutationsAndPreserveEscapeAndRetry() {
        val state = mutableStateOf(DesktopSceneBookmarkPanelState(scope, loading = true))
        var closed = false
        var retried = false
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            Box(Modifier.size(740.dp, 600.dp)) {
                DesktopSceneBookmarksPanel(state.value, 0, false,
                    onSave = { fail("Save during unavailable playback") }, onRename = { _, _ -> fail("Rename during load") },
                    onRemove = {}, onJump = { _, _ -> false }, onRetry = { retried = true }, onDismiss = { closed = true })
            }
        } }
        compose.onNodeWithTag("scene-bookmark-save").assertIsNotEnabled()
        compose.onNodeWithTag("scene-bookmark-close").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(loading = false, failed = true) }
        compose.onNodeWithTag("scene-bookmark-retry").performClick()
        compose.runOnIdle { assertTrue(retried) }
        compose.onNodeWithTag("scene-bookmark-close").performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle { assertTrue(closed); state.value = DesktopSceneBookmarkPanelState(loading = false) }
        compose.onNodeWithTag("scene-bookmark-save").assertIsNotEnabled()
    }

    @Test fun otherSourceConfirmationHasKeyboardFocusAndCannotSilentlySeek() {
        val item = SceneBookmark("fixture-id", scope.mediaId, scope.mediaType, scope.videoId,
            SceneBookmarkScope.sourceEdition("other-cut"), 12_000, "Outra fonte", 1234)
        var jumped = false
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.REDUCED) {
            Box(Modifier.size(740.dp, 600.dp)) {
                DesktopSceneBookmarksPanel(DesktopSceneBookmarkPanelState(scope, listOf(item), loading = false), 0, true,
                    onSave = {}, onRename = { _, _ -> }, onRemove = {},
                    onJump = { id, confirmed -> assertEquals(item.id, id); assertTrue(confirmed); jumped = true; true },
                    onRetry = {}, onDismiss = {})
            }
        } }
        compose.onNodeWithTag("scene-bookmark-jump-${item.id}").performClick()
        compose.runOnIdle { assertFalse(jumped) }
        compose.onNodeWithTag("scene-bookmark-confirm").assertIsFocused()
        compose.onNodeWithTag("scene-bookmark-rename-${item.id}").assertIsNotEnabled()
        activateByKeyboard("scene-bookmark-confirm")
        compose.runOnIdle { assertTrue(jumped) }
        compose.onNodeWithTag("scene-bookmark-confirm").assertDoesNotExist()
    }

    @Test fun failedRenameKeepsTheDraftAndRejectedSeekExplainsTheUnavailableMoment() {
        val item = SceneBookmark("fixture-id", scope.mediaId, scope.mediaType, scope.videoId,
            scope.editionKey, 12_000, "Original", 1234)
        val state = mutableStateOf(DesktopSceneBookmarkPanelState(scope, listOf(item), loading = false))
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            Box(Modifier.size(740.dp, 600.dp)) {
                DesktopSceneBookmarksPanel(state.value, 0, true, onSave = {},
                    onRename = { _, _ -> state.value = state.value.copy(failed = true) },
                    onRemove = {}, onJump = { _, _ -> false },
                    onRetry = { state.value = state.value.copy(failed = false) }, onDismiss = {})
            }
        } }
        compose.onNodeWithTag("scene-bookmark-rename-${item.id}").performClick()
        compose.onNodeWithTag("scene-bookmark-name").performTextReplacement("Tentativa preservada")
        activateByKeyboard("scene-bookmark-save")
        compose.onNodeWithTag("scene-bookmark-name").assertTextContains("Tentativa preservada")
        compose.onNodeWithTag("scene-bookmark-retry").performClick()
        compose.onNodeWithTag("scene-bookmark-name").assertTextContains("Tentativa preservada")
        compose.onNodeWithTag("scene-bookmark-jump-${item.id}").performClick()
        compose.onNodeWithTag("scene-bookmark-seek-error").assertIsDisplayed()
    }

    private fun activateByKeyboard(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.Enter) }
    }
}
