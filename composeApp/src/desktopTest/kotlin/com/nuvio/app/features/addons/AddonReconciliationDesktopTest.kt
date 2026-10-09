package com.nuvio.app.features.addons

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.sync.AccountSyncOwner
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AddonReconciliationDesktopTest {
    private val a = "https://fixture.invalid/manifest.json?config=a"
    private val b = "https://fixture.invalid/manifest.json?config=b"
    private val c = "https://other.invalid/manifest.json"
    private fun snapshot(vararg entries: Pair<String, String>): JsonArray = JsonArray(entries.mapIndexed { index, (url, name) ->
        buildJsonObject { put("url", url); put("name", name); put("enabled", false); put("sort_order", index) }
    })
    private fun urls(snapshot: JsonArray) = snapshot.map { it.jsonObject["url"]!!.jsonPrimitive.content }

    private class RemoteFixture(var server: JsonArray) : AddonSyncRemote {
        var offline = false
        var uploads = 0
        var onPull: () -> Unit = {}
        var onUpload: () -> Unit = {}
        override suspend fun pull(profileId: Int): JsonArray { check(!offline); onPull(); return server }
        override suspend fun push(profileId: Int, snapshot: JsonArray) { check(!offline); uploads++; onUpload(); server = snapshot }
    }

    @Test fun actualOfflineRemovalMergesRemoteAdditionsAndNamesBeforeUploading() = fixture("remove") { _ ->
        runBlocking {
            val remote = RemoteFixture(snapshot(a to "A", b to "B"))
            AddonSyncCoordinator.remote = remote
            AddonRepository.pullFromServer(1)
            remote.offline = true
            AddonRepository.removeAddon(a)
            AddonRepository.clearLocalState() // Cancel debounce without deleting the durable journal.
            assertFails { AddonRepository.pullFromServer(1) }
            AddonRepository.initialize()
            assertEquals(listOf(b), AddonRepository.uiState.value.addons.map { it.manifestUrl })
            remote.offline = false
            remote.server = snapshot(a to "A", b to "Nome remoto", c to "Novo")
            AddonRepository.pullFromServer(1)
            assertEquals(listOf(b, c), urls(remote.server))
            assertEquals("Nome remoto", AddonRepository.uiState.value.addons.first().userSetName)
            assertEquals(1, remote.uploads)
            remote.server = snapshot()
            AddonRepository.pullFromServer(1)
            assertTrue(AddonRepository.uiState.value.addons.isEmpty())
            assertEquals(1, remote.uploads)
        }
    }

    @Test fun disablingAnAddonSurvivesAnIndependentRemoteAddition() = fixture("enabled") { _ ->
        runBlocking {
            val initial = JsonArray(snapshot(a to "A").map { JsonObject(it.jsonObject + ("enabled" to JsonPrimitive(true))) })
            val remote = RemoteFixture(initial)
            AddonSyncCoordinator.remote = remote
            // A loaded manifest prevents this storage/sync fixture from performing network fetches.
            @Suppress("UNCHECKED_CAST")
            val ui = AddonRepository::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
                .get(AddonRepository) as MutableStateFlow<AddonsUiState>
            ui.value = AddonsUiState(listOf(ManagedAddon(a,
                AddonManifest("fixture", "A", "", "1", resources = emptyList(), types = emptyList(), transportUrl = a))))
            AddonRepository.pullFromServer(1)
            AddonRepository.setAddonEnabled(a, false)
            AddonRepository.clearLocalState()
            remote.server = JsonArray(initial + snapshot(c to "Remoto"))
            AddonRepository.pullFromServer(1)
            assertTrue(AddonRepository.uiState.value.addons.none { it.enabled })
            assertEquals(listOf(a, c), urls(remote.server))
            assertEquals(1, remote.uploads)
        }
    }

    @Test fun localReorderingKeepsRemoteNamesAndRebuildsUniqueSortPositions() = fixture("order") { _ ->
        runBlocking {
            val remote = RemoteFixture(snapshot(a to "A", b to "B", c to "C"))
            AddonSyncCoordinator.remote = remote
            AddonRepository.pullFromServer(1)
            AddonRepository.moveAddon(2, 0)
            AddonRepository.clearLocalState()
            remote.server = snapshot(a to "Nome novo", b to "B", c to "C")
            AddonRepository.pullFromServer(1)
            assertEquals(listOf(c, a, b), urls(remote.server))
            assertEquals(listOf(0, 1, 2), remote.server.map { it.jsonObject["sort_order"]!!.jsonPrimitive.int })
            assertEquals("Nome novo", AddonRepository.uiState.value.addons[1].userSetName)
        }
    }

    @Test fun namesDisabledStatesAndConfiguredQueriesSurviveActualStorageRecreation() = fixture("names") { _ ->
        runBlocking {
            val remote = RemoteFixture(snapshot(a to "Brasil", b to "Internacional"))
            AddonSyncCoordinator.remote = remote
            AddonRepository.pullFromServer(1)
            AddonRepository.clearLocalState()
            AddonRepository.initialize()
            assertEquals(listOf(a, b), AddonRepository.uiState.value.addons.map { it.manifestUrl })
            assertEquals(listOf("Brasil", "Internacional"), AddonRepository.uiState.value.addons.map { it.userSetName })
            assertTrue(AddonRepository.uiState.value.addons.none { it.enabled })
            assertEquals(0, remote.uploads)
        }
    }

    @Test fun anotherLocalRemovalDuringUploadIsNotAcknowledgedByTheEarlierRevision() = fixture("during") { _ ->
        runBlocking {
            val remote = RemoteFixture(snapshot(a to "A", b to "B", c to "C"))
            AddonSyncCoordinator.remote = remote
            AddonRepository.pullFromServer(1)
            AddonRepository.removeAddon(c)
            AddonRepository.clearLocalState()
            remote.onUpload = { AddonRepository.removeAddon(a); AddonRepository.clearLocalState() }
            AddonRepository.pullFromServer(1)
            assertEquals(listOf(a, b), urls(remote.server))
            AddonRepository.initialize()
            assertEquals(listOf(b), AddonRepository.uiState.value.addons.map { it.manifestUrl })
            remote.onUpload = {}
            AddonRepository.pullFromServer(1)
            assertEquals(listOf(b), urls(remote.server))
            assertEquals(2, remote.uploads)
        }
    }

    @Test fun accountChangeDuringPullRejectsTheActualRepositoryPublication() = fixture("owner") { auth ->
        runBlocking {
            val remote = RemoteFixture(snapshot(a to "Privado"))
            remote.onPull = { auth.value = AuthState.Authenticated("other", null, false) }
            AddonSyncCoordinator.remote = remote
            assertFailsWith<CancellationException> { AddonRepository.pullFromServer(1) }
            assertTrue(AddonRepository.uiState.value.addons.isEmpty())
            assertEquals(0, remote.uploads)
        }
    }

    @Test fun primaryAddonReadOnlyRefreshDoesNotUploadPendingChanges() = fixture("readonly") { auth ->
        runBlocking {
            val remote = RemoteFixture(snapshot(a to "A"))
            AddonSyncCoordinator.remote = remote
            AddonRepository.pullFromServer(1)
            AddonRepository.removeAddon(a)
            AddonRepository.clearLocalState()
            var applied: JsonArray? = null
            AddonSyncCoordinator.reconcile(AccountSyncOwner((auth.value as AuthState.Authenticated).userId, 1),
                effectiveProfileId = 1, mayUpload = false, stillEffective = { true }) { applied = it }
            assertEquals(snapshot(), applied)
            assertEquals(0, remote.uploads)
            AddonRepository.pullFromServer(1)
            assertEquals(snapshot(), remote.server)
            assertEquals(1, remote.uploads)
        }
    }

    private fun fixture(label: String, block: (MutableStateFlow<AuthState>) -> Unit) {
        check(System.getenv("NUVIO_ENHANCED_ISOLATED_THEME_TEST") == "1")
        @Suppress("UNCHECKED_CAST")
        val auth = AuthRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(AuthRepository) as MutableStateFlow<AuthState>
        val index = ProfileRepository::class.java.getDeclaredField("activeProfileIndex").apply { isAccessible = true }
        val priorIndex = index.getInt(ProfileRepository)
        val priorAuth = auth.value
        val priorRemote = AddonSyncCoordinator.remote
        val priorUrls = AddonStorage.loadInstalledAddonUrls(1)
        val priorEnabled = AddonStorage.loadAddonEnabledStates(1)
        val priorSnapshot = AddonStorage.loadSyncSnapshot(1)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            index.setInt(ProfileRepository, 1)
            auth.value = AuthState.Authenticated("addon-journal-$label", null, false)
            AddonRepository.clearLocalState()
            AddonStorage.saveInstalledAddonUrls(1, emptyList())
            AddonStorage.saveAddonEnabledStates(1, emptyMap())
            AddonStorage.saveSyncSnapshot(1, "[]")
            block(auth)
        } finally {
            AddonRepository.clearLocalState()
            AddonSyncCoordinator.remote = priorRemote
            AddonStorage.saveInstalledAddonUrls(1, priorUrls)
            AddonStorage.saveAddonEnabledStates(1, priorEnabled)
            AddonStorage.saveSyncSnapshot(1, priorSnapshot ?: "[]")
            index.setInt(ProfileRepository, priorIndex)
            auth.value = priorAuth
            Dispatchers.resetMain()
        }
    }
}
