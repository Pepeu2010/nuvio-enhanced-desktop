package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.metadata.EmbeddedChapterSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val chapterJson = Json { ignoreUnknownKeys = true }

internal fun decodeEmbeddedChapters(bytes: ByteArray?): EmbeddedChapterSnapshot {
    if (bytes == null || bytes.isEmpty() || bytes.size > 1024 * 1024) return EmbeddedChapterSnapshot()
    return runCatching {
        val decoded = chapterJson.decodeFromString<EmbeddedChapterSnapshot>(
            bytes.decodeToString(throwOnInvalidSequence = true))
        if (decoded.chapters.size > 512) decoded.copy(chapters = decoded.chapters.take(512), complete = false)
        else decoded
    }.getOrDefault(EmbeddedChapterSnapshot())
}
