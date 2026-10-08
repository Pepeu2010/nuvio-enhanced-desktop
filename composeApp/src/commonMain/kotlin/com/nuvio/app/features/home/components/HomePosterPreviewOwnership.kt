package com.nuvio.app.features.home.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** A disappearing old card must never release the newer card's playback slot. */
internal class HomePosterPreviewOwnership {
    private val current = MutableStateFlow<Any?>(null)
    val active: StateFlow<Any?> = current.asStateFlow()
    fun claim(token: Any) { current.value = token }
    fun release(token: Any) { current.compareAndSet(token, null) }
}

internal val homePosterPreviewOwnership = HomePosterPreviewOwnership()

/** Metadata and trailer resolution share one deadline; leaving the card still cancels the caller. */
internal suspend fun <T> resolveBoundedHoverPreview(timeoutMillis: Long = 5_000, load: suspend () -> T?): T? =
    try {
        withTimeoutOrNull(timeoutMillis) { load() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
