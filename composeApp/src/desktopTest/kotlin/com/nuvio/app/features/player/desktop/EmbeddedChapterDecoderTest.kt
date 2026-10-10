package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.metadata.EmbeddedChapter
import com.nuvio.app.features.player.metadata.EmbeddedChapterSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EmbeddedChapterDecoderTest {
    @Test fun nativeUtf8PreservesUnicodeAndEscapedControlCharacters() {
        val snapshot = EmbeddedChapterSnapshot(listOf(EmbeddedChapter(3, 1500, "Café \"😀\"\nsegundo")), true)
        assertEquals(snapshot, decodeEmbeddedChapters(Json.encodeToString(snapshot).encodeToByteArray()))
    }

    @Test fun malformedInvalidUtf8AndOversizedMessagesCannotPublishPartialMetadata() {
        for (bytes in listOf(null, byteArrayOf(), "{\"chapters\":[".encodeToByteArray(), byteArrayOf(0xc3.toByte(), 0x28), ByteArray(1024 * 1024 + 1))) {
            assertEquals(EmbeddedChapterSnapshot(), decodeEmbeddedChapters(bytes))
        }
    }

    @Test fun oversizedChapterListIsBoundedAndMarkedIncomplete() {
        val input = EmbeddedChapterSnapshot((0..600).map { EmbeddedChapter(it, it.toLong(), "Nome") }, true)
        val decoded = decodeEmbeddedChapters(Json.encodeToString(input).encodeToByteArray())
        assertEquals(512, decoded.chapters.size)
        assertFalse(decoded.complete)
    }
}
