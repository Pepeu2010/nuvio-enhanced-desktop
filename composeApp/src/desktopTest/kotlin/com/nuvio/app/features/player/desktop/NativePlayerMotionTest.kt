package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.ui.AnimationIntensity
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.UiMotionPolicy
import com.nuvio.app.features.player.PlayerControlsState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NativePlayerMotionTest {
    @Test fun profileMotionCrossesTheActualControlsBridgeWithoutChangingPlaybackFields() {
        for (mode in NavigationMotion.entries) for (intensity in AnimationIntensity.entries) {
            val policy = UiMotionPolicy(mode, intensity)
            val controlsJson = PlayerControlsState(
                title = "Áudio e ação 🎬", audioLabel = "Áudio", volumeLevel = 0.75f,
                skipPromptVisible = true, skipPromptEndMs = 12000L,
                motionPolicy = policy,
            ).toControlsJson(isFullscreen = true)
            assertFalse(controlsJson.any { it.code in 0xD800..0xDFFF })
            val fields = Json.parseToJsonElement(controlsJson).jsonObject
            assertEquals(mode.name, fields.getValue("navigationMotion").jsonPrimitive.content)
            assertEquals(intensity.fraction, fields.getValue("animationIntensity").jsonPrimitive.content.toFloat())
            for ((name, base) in listOf("motionFastMillis" to 120, "motionStandardMillis" to 180, "motionPanelMillis" to 220)) {
                assertEquals(policy.durationMillis(base), fields.getValue(name).jsonPrimitive.content.toInt())
            }
            assertEquals("Áudio e ação 🎬", fields.getValue("title").jsonPrimitive.content)
            assertEquals("Áudio", fields.getValue("audioLabel").jsonPrimitive.content)
            assertEquals("true", fields.getValue("isFullscreen").jsonPrimitive.content)
            assertEquals("true", fields.getValue("skipPromptVisible").jsonPrimitive.content)
            assertEquals("12000", fields.getValue("skipPromptEndMs").jsonPrimitive.content)
            assertEquals(0.75f, fields.getValue("volumeLevel").jsonPrimitive.content.toFloat())
        }
    }
}
