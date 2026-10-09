package com.nuvio.app.features.collection

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionJournalDesktopTest {
    private class RemoteFixture(var server: JsonArray) : CollectionSyncRemote {
        var offline = false
        var beforeResponse: () -> Unit = {}
        var duringUpload: () -> Unit = {}
        var uploads = 0
        override suspend fun pull(profileId: Int): SupabaseCollectionBlob {
            check(!offline) { "Fixture offline" }
            beforeResponse()
            return SupabaseCollectionBlob(profileId, server)
        }
        override suspend fun push(profileId: Int, payload: JsonArray) {
            check(!offline) { "Fixture offline" }
            uploads++
            duringUpload()
            server = payload
        }
    }

    @Test fun actualReconciliationRecoversOfflineEditsAndKeepsIndependentRemoteAdditions() = withOwnedFixture("merge") { auth ->
        runBlocking {
            auth.value = AuthState.Authenticated("journal-merge", null, false)
            CollectionRepository.clearLocalState()
            CollectionStorage.savePayload("[]")
            val backend = RemoteFixture(Json.parseToJsonElement("""[{"id":"a","title":"Antes","folders":[]}]""").jsonArray)
            val priorRemote = CollectionSyncService.remote
            CollectionSyncService.remote = backend
            try {
                CollectionSyncService.pullFromServer(1)
                backend.offline = true
                CollectionRepository.updateCollection(CollectionRepository.getCollection("a")!!.copy(title = "Edição offline"))
                assertFails { CollectionSyncService.pullFromServer(1) }
                CollectionRepository.clearLocalState()
                CollectionRepository.initialize()
                assertEquals("Edição offline", CollectionRepository.getCollection("a")!!.title)
                backend.offline = false
                backend.server = Json.parseToJsonElement("""[{"id":"a","title":"Antes","folders":[],"future":"preserved"},{"id":"b","title":"Nuvio","folders":[]}]""").jsonArray
                CollectionSyncService.pullFromServer(1)
                assertEquals(setOf("a", "b"), CollectionRepository.collections.value.map { it.id }.toSet())
                assertEquals("Edição offline", CollectionRepository.getCollection("a")!!.title)
                assertEquals("preserved", backend.server.first().jsonObject["future"]!!.jsonPrimitive.content)
                assertEquals(1, backend.uploads)
                backend.server = JsonArray(emptyList())
                CollectionSyncService.pullFromServer(1)
                assertTrue(CollectionRepository.collections.value.isEmpty())
                assertEquals(1, backend.uploads) // Confirmed remote deletion is not resurrected.
            } finally { CollectionSyncService.remote = priorRemote }
        }
    }

    @Test fun importingAReplacementPreservesOfflineDeletionsOnReconciliation() = withOwnedFixture("import") { auth ->
        runBlocking {
            auth.value = AuthState.Authenticated("journal-import", null, false)
            CollectionRepository.clearLocalState()
            CollectionStorage.savePayload("[]")
            val backend = RemoteFixture(Json.parseToJsonElement("""[{"id":"old","title":"Antes","folders":[]}]""").jsonArray)
            val priorRemote = CollectionSyncService.remote
            CollectionSyncService.remote = backend
            try {
                CollectionSyncService.pullFromServer(1)
                CollectionRepository.importFromJson("""[{"id":"new","title":"Importada","folders":[]}]""").getOrThrow()
                backend.server = Json.parseToJsonElement("""[{"id":"old","title":"Antes","folders":[]},{"id":"remote","title":"Outra","folders":[]}]""").jsonArray
                CollectionSyncService.pullFromServer(1)
                assertEquals(setOf("new", "remote"), backend.server.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
                assertEquals(setOf("new", "remote"), CollectionRepository.collections.value.map { it.id }.toSet())
            } finally { CollectionSyncService.remote = priorRemote }
        }
    }

    @Test fun editingDuringUploadRemainsPendingAndIsSentOnTheNextReconciliation() = withOwnedFixture("during-upload") { auth ->
        runBlocking {
            auth.value = AuthState.Authenticated("journal-during-upload", null, false)
            CollectionRepository.clearLocalState()
            CollectionStorage.savePayload("[]")
            val backend = RemoteFixture(JsonArray(emptyList()))
            val priorRemote = CollectionSyncService.remote
            CollectionSyncService.remote = backend
            try {
                CollectionRepository.addCollection(Collection("a", "Primeiro"))
                backend.duringUpload = {
                    CollectionRepository.updateCollection(CollectionRepository.getCollection("a")!!.copy(title = "Último"))
                }
                CollectionSyncService.pullFromServer(1)
                assertEquals("Último", CollectionRepository.getCollection("a")!!.title)
                assertEquals("Primeiro", backend.server.first().jsonObject["title"]!!.jsonPrimitive.content)
                backend.duringUpload = {}
                CollectionSyncService.pullFromServer(1)
                assertEquals("Último", backend.server.first().jsonObject["title"]!!.jsonPrimitive.content)
                assertEquals(2, backend.uploads)
            } finally { CollectionSyncService.remote = priorRemote }
        }
    }

    @Test fun accountChangeDuringTheActualPullPreventsSnapshotApplicationAndUpload() = withOwnedFixture("stale-account") { auth ->
        runBlocking {
            auth.value = AuthState.Authenticated("journal-stale-a", null, false)
            CollectionRepository.clearLocalState()
            CollectionStorage.savePayload("[]")
            val backend = RemoteFixture(Json.parseToJsonElement("""[{"id":"remote","title":"Remoto","folders":[]}]""").jsonArray)
            backend.beforeResponse = { auth.value = AuthState.Authenticated("journal-stale-b", null, false) }
            val priorRemote = CollectionSyncService.remote
            CollectionSyncService.remote = backend
            try {
                assertFailsWith<kotlinx.coroutines.CancellationException> { CollectionSyncService.pullFromServer(1) }
                assertTrue(CollectionRepository.collections.value.isEmpty())
                assertEquals(0, backend.uploads)
            } finally { CollectionSyncService.remote = priorRemote }
        }
    }
    @Test fun offlineMutationRecoversThroughTheRealRepositoryAndDiskStorage() = withOwnedFixture("recovery") { auth ->
        auth.value = AuthState.Authenticated("journal-recovery-a", null, false)
        CollectionRepository.clearLocalState()
        CollectionStorage.savePayload("[]")
        CollectionRepository.addCollection(Collection("offline", "Minha coleção"))
        val expected = CollectionRepository.exportToJson()
        // Simulate a crash after the durable journal write but before the ordinary snapshot write.
        CollectionStorage.savePayload("[]")
        CollectionRepository.clearLocalState()
        CollectionRepository.initialize()
        assertEquals(expected, CollectionRepository.exportToJson())
        assertEquals("Minha coleção", CollectionRepository.getCollection("offline")?.title)
        auth.value = AuthState.Authenticated("journal-recovery-b", null, false)
        CollectionRepository.clearLocalState()
        CollectionRepository.initialize()
        assertTrue(CollectionRepository.collections.value.isEmpty())
    }

    @Test fun futureJournalIsKeptAndOrdinaryLocalDataRemainsReadable() = withOwnedFixture("future") { auth ->
        val userId = "journal-future"
        auth.value = AuthState.Authenticated(userId, null, false)
        val ownerKey = JsonArray(listOf(JsonPrimitive(ServerConfigurationRepository.active.value.backendUrl),
            JsonPrimitive(userId), JsonPrimitive(1))).toString().encodeToByteArray()
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        val future = """{"schema":99,"local":[],"baseline":[]}"""
        CollectionStorage.saveSyncJournal(ownerKey, future)
        val ordinary = """[{"id":"existing","title":"Preservada","folders":[]}]"""
        CollectionStorage.savePayload(ordinary)
        CollectionRepository.clearLocalState()
        CollectionRepository.initialize()
        assertEquals("Preservada", CollectionRepository.getCollection("existing")?.title)
        assertEquals(future, CollectionStorage.loadSyncJournal(ownerKey))
        assertFails { CollectionSyncService.recordLocalChange(JsonArray(emptyList()),
            Json.parseToJsonElement(ordinary).jsonArray) }
        assertEquals(future, CollectionStorage.loadSyncJournal(ownerKey))
    }

    private fun withOwnedFixture(label: String, block: (MutableStateFlow<AuthState>) -> Unit) {
        check(System.getenv("NUVIO_ENHANCED_ISOLATED_THEME_TEST") == "1") { "Owned isolated APPDATA required: $label" }
        @Suppress("UNCHECKED_CAST")
        val auth = AuthRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(AuthRepository) as MutableStateFlow<AuthState>
        val index = ProfileRepository::class.java.getDeclaredField("activeProfileIndex").apply { isAccessible = true }
        val priorIndex = index.getInt(ProfileRepository)
        val priorAuth = auth.value
        index.setInt(ProfileRepository, 1)
        val priorPayload = CollectionStorage.loadPayload()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try { block(auth) }
        finally {
            CollectionRepository.clearLocalState()
            CollectionStorage.savePayload(priorPayload ?: "[]")
            index.setInt(ProfileRepository, priorIndex)
            auth.value = priorAuth
            Dispatchers.resetMain()
        }
    }
}
