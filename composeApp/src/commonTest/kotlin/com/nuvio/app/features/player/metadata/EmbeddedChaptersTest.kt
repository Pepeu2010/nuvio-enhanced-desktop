package com.nuvio.app.features.player.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedChaptersTest {
    private val scope = TimedMetadataScope("movie", "movie", "movie", "edition")
    private fun timeline(chapters: List<EmbeddedChapter>, duration: Long = 6000, complete: Boolean = true) =
        embeddedChapterTimedMetadata(scope, EmbeddedChapterSnapshot(chapters, complete), duration)

    @Test fun unsortedFileChaptersPreserveNativeIdentityAndHalfOpenBoundaries() {
        val result = timeline(listOf(EmbeddedChapter(7, 3000, "Final"), EmbeddedChapter(2, 0, "Começo")))
        assertEquals(listOf("2:0", "7:3000"), result.events.map { it.id })
        assertEquals(listOf(3000L, 6000L), result.events.map { it.endMs })
        assertEquals("Começo", (result.eventsAt(2999).single().body as TimedMetadataBody.Chapter).title)
        assertEquals("Final", (result.eventsAt(3000).single().body as TimedMetadataBody.Chapter).title)
        assertTrue(result.eventsAt(6000).isEmpty())
    }

    @Test fun untitledChapterBoundsPrecedingTitleWithoutInventingMetadata() {
        val result = timeline(listOf(EmbeddedChapter(0, 0, "Começo"), EmbeddedChapter(1, 2000), EmbeddedChapter(2, 4000, "Final")))
        assertEquals(2, result.events.size)
        assertEquals(2000L, result.events.first().endMs)
        assertTrue(result.eventsAt(2500).isEmpty())
    }

    @Test fun incompleteOrUnknownDurationDoesNotInventAnEndForLastChapter() {
        assertNull(timeline(listOf(EmbeddedChapter(0, 0, "Título")), complete = false).events.single().endMs)
        assertNull(timeline(listOf(EmbeddedChapter(0, 0, "Título")), duration = 0).events.single().endMs)
    }

    @Test fun invalidAndDuplicatePositionsCannotMakeALastIntervalComplete() {
        val result = timeline(listOf(EmbeddedChapter(-1, 0, "Inválido"), EmbeddedChapter(0, 1000, "Primeiro"),
            EmbeddedChapter(1, 1000, "Duplicado"), EmbeddedChapter(2, -1, "Negativo"), EmbeddedChapter(3, 6000, "Fora")))
        assertEquals("Primeiro", (result.events.single().body as TimedMetadataBody.Chapter).title)
        assertNull(result.events.single().endMs)
    }

    @Test fun oversizedInputIsBoundedWithoutExtendingItsTruncatedLastChapter() {
        val result = timeline((0..700).map { EmbeddedChapter(it, it * 10L, "Capítulo $it") }, 10000)
        assertEquals(512, result.events.size)
        assertNull(result.events.last().endMs)
    }

    @Test fun chapterTitleCannotBecomeATrustedCastOrMusicClaim() {
        val result = timeline(listOf(EmbeddedChapter(0, 0, "Pessoa na cena")))
        assertNull(result.events.single().confidence)
        assertEquals("embedded-mpv", result.events.single().provenance.providerId)
        assertTrue(result.sceneEventsAt(1000).isEmpty())
        assertEquals(scope, result.scope)
    }

    @Test fun emptyOversizedAndUnicodeTitlesUseTheExistingMarkerContract() {
        val result = timeline(listOf(EmbeddedChapter(0, 0, " "), EmbeddedChapter(1, 1000, "x".repeat(257)),
            EmbeddedChapter(2, 2000, "  Café \"😀\"  ")))
        val marker = result.toPlayerMarkers(6000) { "unused" }.single()
        assertEquals("Café \"😀\"", marker.label)
        assertEquals(TimedMetadataKind.CHAPTER, marker.kind)
        assertEquals("embedded-mpv:2:2000", marker.id)
    }
}
