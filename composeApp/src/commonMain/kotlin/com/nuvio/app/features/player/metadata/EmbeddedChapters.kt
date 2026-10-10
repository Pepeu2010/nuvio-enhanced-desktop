package com.nuvio.app.features.player.metadata

import kotlinx.serialization.Serializable

/** Chapter positions from the currently opened file; never inferred from an episode description. */
@Serializable
data class EmbeddedChapter(val index: Int, val startMs: Long, val title: String = "")

@Serializable
data class EmbeddedChapterSnapshot(
    val chapters: List<EmbeddedChapter> = emptyList(),
    val complete: Boolean = false,
)

/** Untitled chapters still bound their named neighbors, but do not acquire an invented title. */
internal fun embeddedChapterTimedMetadata(
    scope: TimedMetadataScope,
    snapshot: EmbeddedChapterSnapshot,
    durationMs: Long,
): TimedMetadataTimeline {
    val bounded = snapshot.chapters.take(512).filter {
        it.index >= 0 && it.startMs >= 0 && it.startMs < Long.MAX_VALUE &&
            (durationMs <= 0 || it.startMs < durationMs)
    }.sortedWith(compareBy({ it.startMs }, { it.index })).distinctBy { it.startMs }
    val complete = snapshot.complete && snapshot.chapters.size <= 512 &&
        bounded.size == snapshot.chapters.size
    val events = bounded.mapIndexedNotNull { ordinal, chapter ->
        val title = chapter.title.trim()
        if (title.isBlank() || title.length > 256) return@mapIndexedNotNull null
        val end = bounded.getOrNull(ordinal + 1)?.startMs
            ?: durationMs.takeIf { complete && it > chapter.startMs }
        TimedMetadataEvent(
            id = "${chapter.index}:${chapter.startMs}", startMs = chapter.startMs, endMs = end,
            body = TimedMetadataBody.Chapter(title),
            provenance = TimedMetadataProvenance("embedded-mpv"),
        )
    }
    // Embedded chapter titles are not actor/music claims. Scene confidence remains unknown.
    return TimedMetadataTimeline.create(scope, events, durationMs.takeIf { it > 0 })
}
