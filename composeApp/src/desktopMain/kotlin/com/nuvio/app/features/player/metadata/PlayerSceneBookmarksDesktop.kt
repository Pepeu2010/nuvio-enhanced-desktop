package com.nuvio.app.features.player.metadata

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DesktopSceneBookmarkPanelState(
    val scope: SceneBookmarkScope? = null,
    val items: List<SceneBookmark> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

/** Uses the existing libmpv controller's snapshot and seek, with durable local profile ownership. */
internal class PlayerSceneBookmarksDesktop(
    private val store: SceneBookmarkStore,
    private val scopes: StateFlow<SceneBookmarkScope?>,
    private val coroutineScope: CoroutineScope,
    private val playback: () -> PlayerPlaybackSnapshot,
    private val seek: (Long) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val revision = MutableStateFlow(0L)
    private val mutable = MutableStateFlow(DesktopSceneBookmarkPanelState())
    val state = mutable.asStateFlow()

    init {
        coroutineScope.launch {
            combine(scopes, revision) { scope, _ -> scope }.collectLatest { scope ->
                mutable.value = DesktopSceneBookmarkPanelState(scope, loading = scope != null)
                if (scope != null) {
                    try {
                        val items = withContext(io) { store.all(scope) }
                        if (current(scope)) mutable.value = DesktopSceneBookmarkPanelState(scope, items, loading = false)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (current(scope)) mutable.value = DesktopSceneBookmarkPanelState(scope, loading = false, failed = true)
                    }
                }
            }
        }
    }

    fun canSave(point: PlayerPlaybackSnapshot = playback()): Boolean {
        val scope = state.value.scope ?: return false
        return current(scope) && !point.isLoading && point.durationMs > 0 && point.positionMs in 0 until point.durationMs &&
            !state.value.loading && !state.value.busy && !state.value.failed
    }

    fun save(name: String) {
        val point = playback()
        if (!canSave(point)) return
        mutate { scope -> store.save(scope, point.positionMs, point.durationMs, name) }
    }
    fun rename(id: String, name: String) = mutate { store.rename(it, id, name) }
    fun remove(id: String) = mutate { store.remove(it, id) }
    fun retry() { revision.value += 1 }

    fun jump(id: String, confirmOtherEdition: Boolean = false): Boolean {
        val value = state.value
        val scope = value.scope ?: return false
        val item = value.items.firstOrNull { it.id == id } ?: return false
        val point = playback()
        if (!current(scope) || value.loading || value.busy || value.failed || point.isLoading ||
            point.durationMs <= 0 || item.positionMs !in 0 until point.durationMs) return false
        if (item.editionKey != scope.editionKey && !confirmOtherEdition) return false
        seek(item.positionMs)
        return true
    }

    fun markers(durationMs: Long): List<PlayerTimedMarker> {
        val value = state.value
        val scope = value.scope ?: return emptyList()
        if (!current(scope) || value.loading || value.failed) return emptyList()
        return value.items.filter { it.editionKey == scope.editionKey }.toBookmarkMarkers(durationMs)
    }

    private fun current(scope: SceneBookmarkScope): Boolean = scopes.value == scope

    private fun mutate(action: (SceneBookmarkScope) -> Any) {
        val value = state.value
        val scope = value.scope ?: return
        if (!current(scope) || value.loading || value.busy || value.failed) return
        mutable.value = value.copy(busy = true)
        coroutineScope.launch {
            try {
                val items = withContext(io) {
                    if (!current(scope)) throw CancellationException("Bookmark owner changed")
                    action(scope)
                    store.all(scope)
                }
                if (current(scope)) mutable.value = DesktopSceneBookmarkPanelState(scope, items, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current(scope)) mutable.value = value.copy(busy = false, failed = true)
            }
        }
    }
}
