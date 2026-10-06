package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.metadata.PlayerTimedMarker
import com.nuvio.app.features.player.metadata.TimedMetadataKind
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimedMetadataControlsJsonTest {
    @Test fun actualNativePayloadPreservesEscapedLabelsAndRealPositions() {
        val label = "Abertura \"especial\"\n<script>conteúdo</script>"
        val marker = PlayerTimedMarker("id:1", TimedMetadataKind.INTRO, 0.1f, 0.2f, label, "introdb")
        val state = PlayerControlsState(title = "Título", durationMs = 100_000, positionMs = 12_000, timedMarkers = listOf(marker))
        val payload = Json.parseToJsonElement(state.toControlsJson(false)).jsonObject
        val encoded = payload.getValue("timedMarkers").jsonArray.single().jsonObject
        assertEquals(label, encoded.getValue("label").jsonPrimitive.content)
        assertEquals(0.1f, encoded.getValue("startFraction").jsonPrimitive.float)
        assertEquals(12_000L, payload.getValue("positionMs").jsonPrimitive.long)
        assertEquals(100_000L, payload.getValue("durationMs").jsonPrimitive.long)
        assertEquals("Título", payload.getValue("title").jsonPrimitive.content)
    }

    @Test fun bridgeBoundsMarkerCountAndNonfiniteValuesNeverProduceInvalidJson() {
        val marker = PlayerTimedMarker("id", TimedMetadataKind.CHAPTER, Float.NaN, Float.POSITIVE_INFINITY, "Capítulo", "provider")
        val payload = Json.parseToJsonElement(PlayerControlsState(timedMarkers = List(1000) {marker}).toControlsJson(false)).jsonObject
        val markers = payload.getValue("timedMarkers").jsonArray
        assertEquals(256, markers.size)
        assertTrue(markers.all { it.jsonObject.getValue("startFraction") == JsonNull && it.jsonObject.getValue("endFraction") == JsonNull })
    }
}
