package com.nuvio.app.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TelumiaPosterInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun portraitKeyboardNavigationAndSecondaryActionsRetainSourceAnchor() = verify(NuvioPosterShape.Poster)
    @Test fun landscapeKeyboardNavigationAndSecondaryActionsRetainSourceAnchor() = verify(NuvioPosterShape.Landscape)

    private fun verify(shape: NuvioPosterShape) {
        var openCount = 0
        var actionCount = 0
        var capturedAnchor: PosterZoomAnchor? = null
        compose.setContent {
            NuvioTheme(navigationMotion = NavigationMotion.OFF) {
                NuvioPosterCard(title = "Uma história", imageUrl = null, shape = shape,
                    onClick = { openCount++ }, onLongClick = {
                        actionCount++
                        capturedAnchor = PosterZoomAnchorHolder.consume()
                    })
            }
        }
        val card = compose.onNode(hasClickAction() and hasText("Uma história"))
        card.assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
        card.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        card.performMouseInput { click(button = MouseButton.Secondary) }
        compose.runOnIdle {
            assertEquals(1, openCount)
            assertEquals(1, actionCount)
            assertNotNull(capturedAnchor)
        }
        PosterZoomAnchorHolder.consume()
    }
}
