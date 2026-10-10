@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.nuvio.app.features.player.metadata

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileAvatarScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.*

class PlayerSceneBookmarksDesktopTest {
    private val identity = SceneBookmarkScope(ProfileAvatarScope("fixture-account", 2, "stable-profile"),
        "tt12879200", "series", "tt12879200:1:2", SceneBookmarkScope.sourceEdition("br-cut"))

    @Test fun realPlayerScopeRequiresAnOwnerSourceAndIdentifiedEpisode() {
        val metadata = TimedMetadataScope(identity.mediaId, identity.mediaType, identity.videoId)
        assertEquals(identity, SceneBookmarkScope.fromPlayer(identity.owner, metadata, "br-cut", true))
        assertNull(SceneBookmarkScope.fromPlayer(null, metadata, "br-cut", true))
        assertNull(SceneBookmarkScope.fromPlayer(identity.owner, null, "br-cut", true))
        assertNull(SceneBookmarkScope.fromPlayer(identity.owner, metadata, "br-cut", false))
        assertNull(SceneBookmarkScope.fromPlayer(identity.owner, metadata, "", true))
        assertNull(SceneBookmarkScope.fromPlayer(identity.owner, metadata, "a".repeat(16385), true))
        assertNull(SceneBookmarkScope.fromPlayer(identity.owner, metadata.copy(videoId = identity.mediaId), "br-cut", true))
        assertNull(SceneBookmarkScope.fromPlayer(identity.owner, metadata.copy(videoId = ""), "br-cut", true))
        val movie = metadata.copy(mediaType = "movie", videoId = metadata.mediaId)
        assertNotNull(SceneBookmarkScope.fromPlayer(identity.owner, movie, "br-cut", true))
    }

    private class Fixture(val root: Path, val store: SceneBookmarkStore,
        val scopes: MutableStateFlow<SceneBookmarkScope?>) {
        var point = PlayerPlaybackSnapshot(isLoading = false, durationMs = 100_000, positionMs = 32_180)
        val jumps = mutableListOf<Long>()
        lateinit var player: PlayerSceneBookmarksDesktop
    }
    private suspend fun TestScope.fixture(
        storeFactory: (Path) -> SceneBookmarkStore = { SceneBookmarkStore(it) },
        queuedIo: Boolean = false,
        action: suspend TestScope.(Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("telumia-player-bookmarks-")
        val fixture = Fixture(root, storeFactory(root), MutableStateFlow(identity))
        fixture.player = PlayerSceneBookmarksDesktop(fixture.store, fixture.scopes, backgroundScope,
            { fixture.point }, { fixture.jumps += it },
            if (queuedIo) StandardTestDispatcher(testScheduler) else UnconfinedTestDispatcher(testScheduler))
        try { runCurrent(); action(fixture) }
        finally {
            backgroundScope.coroutineContext.cancelChildren()
            runCurrent()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test fun savesTheRealSnapshotThenRenamesSeeksAndRemovesTheSameDurableMoment() = runTest {
        fixture { f ->
            assertTrue(f.player.canSave())
            f.player.save("Cena para rever 🎬"); runCurrent()
            val item = f.player.state.value.items.single()
            assertEquals(32_180L, item.positionMs)
            assertEquals(listOf(item), SceneBookmarkStore(f.root).load(identity))
            assertEquals(1, f.player.markers(100_000).size)
            f.player.rename(item.id, "Áudio e ação"); runCurrent()
            assertEquals("Áudio e ação", f.player.state.value.items.single().name)
            assertTrue(f.player.jump(item.id))
            assertEquals(listOf(32_180L), f.jumps)
            f.player.remove(item.id); runCurrent()
            assertTrue(f.player.state.value.items.isEmpty())
            assertTrue(SceneBookmarkStore(f.root).load(identity).isEmpty())
        }
    }

    @Test fun refusesUnavailableLoadingAndOutOfRangePlaybackInsteadOfInventingATimestamp() = runTest {
        fixture { f ->
            for (point in listOf(f.point.copy(durationMs = 0), f.point.copy(isLoading = true),
                f.point.copy(positionMs = 100_000), f.point.copy(positionMs = -1))) {
                f.point = point
                assertFalse(f.player.canSave()); f.player.save("Unavailable"); runCurrent()
                assertTrue(f.store.all(identity).isEmpty())
            }
            f.scopes.value = null; runCurrent()
            assertFalse(f.player.canSave()); assertNull(f.player.state.value.scope)
            assertFalse(f.player.jump("unknown"))
        }
    }

    @Test fun ownerChangeBeforeQueuedIoDoesNotWriteOrPublishThePreviousProfile() = runTest {
        fixture(queuedIo = true) { f ->
            f.player.save("Pending")
            // Schedule the operation without executing the IO dispatcher, then change owner.
            f.scopes.value = identity.copy(owner = identity.owner.copy(accountId = "other-account"))
            f.player.save("Old scope"); runCurrent()
            assertTrue(f.player.state.value.items.isEmpty())
            assertEquals(f.scopes.value, f.player.state.value.scope)
            assertTrue(f.store.all(identity).isEmpty())
            assertTrue(f.player.markers(100_000).isEmpty())
        }
    }

    @Test fun changedSourceRequiresExplicitConfirmationAndNeverProjectsOldMarkersOntoTheNewCut() = runTest {
        fixture { f ->
            f.player.save("Edição anterior"); runCurrent()
            val item = f.player.state.value.items.single()
            f.scopes.value = identity.copy(editionKey = SceneBookmarkScope.sourceEdition("renewed-source")); runCurrent()
            assertEquals(listOf(item), f.player.state.value.items)
            assertTrue(f.player.markers(100_000).isEmpty())
            assertFalse(f.player.jump(item.id)); assertTrue(f.jumps.isEmpty())
            assertTrue(f.player.jump(item.id, confirmOtherEdition = true))
            f.point = f.point.copy(durationMs = 10_000)
            assertFalse(f.player.jump(item.id, confirmOtherEdition = true))
            f.scopes.value = identity.copy(videoId = "tt12879200:1:3"); runCurrent()
            assertTrue(f.player.state.value.items.isEmpty())
            assertFalse(f.player.jump(item.id, true))
        }
    }

    @Test fun failedPublicationPreservesPreviousFileAndOffersARetryWithoutClaimingTheNewName() = runTest {
        var reject = false
        fixture(storeFactory = { root -> SceneBookmarkStore(root) { source, target ->
            if (reject) throw java.io.IOException("Controlled publication failure")
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        } }) { f ->
            f.player.save("Original"); runCurrent()
            val item = f.player.state.value.items.single()
            reject = true; f.player.rename(item.id, "Nova tentativa"); runCurrent()
            assertTrue(f.player.state.value.failed)
            assertEquals("Original", f.store.all(identity).single().name)
            assertEquals("Original", f.player.state.value.items.single().name)
            reject = false; f.player.retry(); runCurrent()
            assertFalse(f.player.state.value.failed)
            f.player.rename(item.id, "Nome aprovado"); runCurrent()
            assertEquals("Nome aprovado", SceneBookmarkStore(f.root).all(identity).single().name)
        }
    }
}
