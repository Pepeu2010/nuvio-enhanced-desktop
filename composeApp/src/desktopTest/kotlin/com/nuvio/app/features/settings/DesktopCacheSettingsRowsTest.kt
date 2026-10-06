package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.nuvio.app.core.storage.*
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopCacheSettingsRowsTest {
    @Test fun manualBudgetAndAutoSelectionCallTheActualSettingsContract() = runDesktopComposeUiTest(width = 1366, height = 768) {
        val settings = mutableStateOf(MediaCacheSettings(MediaCacheMode.MANUAL, 1024L * MIB))
        var autoLabel = ""
        var restartLabel = ""
        val budget = MediaCacheBudget(1024L * MIB, 1024L * MIB, emptySet())
        setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            autoLabel = stringResource(Res.string.settings_cache_auto)
            restartLabel = stringResource(Res.string.settings_cache_restart)
            Column(Modifier.testTag("cache-settings")) { DesktopCacheSettingsRows(settings.value, budget, false) { settings.value = it } }
        } }
        onNodeWithText("2048 MiB").performClick()
        runOnIdle { assertEquals(2048L * MIB, settings.value.manualBytes) }
        onNodeWithText(restartLabel).assertIsDisplayed()
        val image = onNodeWithTag("cache-settings").captureToImage().toAwtImage()
        val file = File("build/telumia-cache-ui/desktop-cache-settings.png").also { it.parentFile.mkdirs() }
        ImageIO.write(image, "png", file)
        onNodeWithText(autoLabel).performClick()
        runOnIdle { assertEquals(MediaCacheMode.AUTO, settings.value.mode) }
        onNodeWithText("2048 MiB").assertDoesNotExist()
    }

    @Test fun savingDisablesEditsAndFailedSaveKeepsTheExistingSelectionReviewable() = runDesktopComposeUiTest {
        val saving = mutableStateOf(true)
        val failed = mutableStateOf(false)
        var autoLabel = ""
        var errorLabel = ""
        var calls = 0
        setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            autoLabel = stringResource(Res.string.settings_cache_auto)
            errorLabel = stringResource(Res.string.settings_cache_save_error)
            DesktopCacheSettingsRows(MediaCacheSettings(MediaCacheMode.MANUAL, 1024L * MIB),
                MediaCacheBudget(1024L * MIB, 1024L * MIB, emptySet()), false, saving.value, failed.value) { calls++ }
        } }
        onNodeWithText("2048 MiB").assertIsNotEnabled()
        runOnIdle { saving.value = false; failed.value = true }
        onNodeWithText(errorLabel).assertIsDisplayed()
        onNodeWithText("1024 MiB").assertIsSelected()
        onNodeWithText(autoLabel).performClick()
        runOnIdle { assertEquals(1, calls) }
    }
}
