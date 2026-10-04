package com.nuvio.app.features.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/** Exercises the same existing adaptive picker now used by Appearance. */
class NavigationMotionPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun existingPickerSupportsMouseAndKeyboardWithoutAddingAnotherSettingsFlow() {
        val selected = mutableStateOf(NavigationMotion.FULL)
        val choices = listOf(
            TrackingPickerOption(NavigationMotion.FULL, "Completo"),
            TrackingPickerOption(NavigationMotion.REDUCED, "Reduzido"),
            TrackingPickerOption(NavigationMotion.OFF, "Desligado"),
        )
        compose.setContent {
            NuvioTheme(navigationMotion = selected.value) {
                TrackingAdaptivePicker(
                    isTablet = true,
                    title = "Movimento de navegação",
                    subtitle = "Controla as transições entre telas. Outros efeitos mantêm suas configurações atuais.",
                    selectedValue = selected.value,
                    options = choices,
                    onSelected = { selected.value = it },
                    onDismiss = {},
                )
            }
        }
        savePreview("full")
        compose.onNodeWithText("Reduzido").performClick()
        compose.runOnIdle { assertEquals(NavigationMotion.REDUCED, selected.value) }
        savePreview("reduced")
        compose.onNodeWithText("Desligado").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Desligado").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(NavigationMotion.OFF, selected.value) }
        savePreview("off")
    }

    private fun savePreview(name: String) {
        val output = File("build/reports/navigation-motion").apply { mkdirs() }
        val root = compose.onNode(isRoot() and hasAnyDescendant(hasText("Movimento de navegação")))
        ImageIO.write(root.captureToImage().toAwtImage(), "png", File(output, "$name.png"))
    }
}
