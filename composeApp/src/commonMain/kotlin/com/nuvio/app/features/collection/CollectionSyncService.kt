package com.nuvio.app.features.collection

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.sync.AccountSyncOwner
import com.nuvio.app.core.sync.SnapshotSyncJournal
import com.nuvio.app.core.sync.accountSyncOwner
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.concurrent.Volatile

object CollectionSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("CollectionSyncService")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val syncMutex = Mutex()
    private val journalLock = SynchronizedObject()
    private val journals = linkedMapOf<String, SnapshotSyncJournal>()
    @Volatile var isSyncingFromRemote = false
    private var pushJob: Job? = null
    private var observeJob: Job? = null
    internal var remote: CollectionSyncRemote = SupabaseCollectionSyncRemote

    private fun journal(owner: AccountSyncOwner): SnapshotSyncJournal {
        require(owner.userId.length <= 512 && owner.backendUrl.length <= 2048)
        val ownerKey = JsonArray(listOf(JsonPrimitive(owner.backendUrl), JsonPrimitive(owner.userId), JsonPrimitive(owner.profileId)))
            .toString().encodeToByteArray().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return synchronized(journalLock) {
            journals[ownerKey] ?: SnapshotSyncJournal(
                read = { CollectionStorage.loadSyncJournal(ownerKey) },
                write = { payload ->
                    check(owner.matches(AuthRepository.state.value, ProfileRepository.activeProfileId)) { "Collection journal owner changed" }
                    CollectionStorage.saveSyncJournal(ownerKey, payload)
                }).also {
                if (journals.size >= 24) journals.remove(journals.keys.first())
                journals[ownerKey] = it
            }
        }
    }

    /** Called synchronously before publishing a local mutation; the journal survives a failed upload. */
    internal fun recordLocalChange(previous: JsonArray, next: JsonArray): AccountSyncOwner? {
        val owner = accountSyncOwner() ?: return null
        if (previous != next) journal(owner).recordLocal(previous, next)
        return owner
    }

    internal fun recoverLocalPayload(stored: String?): String? {
        val owner = accountSyncOwner() ?: return stored
        return try { journal(owner).pending()?.second?.toString() ?: stored }
        catch (e: Exception) {
            log.w { "Collection journal recovery deferred: ${e::class.simpleName}" }
            stored // Preserve both documents; an unsupported journal is never rewritten.
        }
    }

    fun startObserving() {
        if (observeJob?.isActive == true) return
        observeLocalChangesAndPush()
    }

    suspend fun pullFromServer(profileId: Int) {
        val owner = accountSyncOwner(profileId) ?: return
        syncMutex.withLock { reconcile(owner) }
    }

    private suspend fun reconcile(owner: AccountSyncOwner) {
        owner.requireCurrent()
        val blob = remote.pull(owner.profileId)
        owner.requireCurrent()
        check(blob == null || blob.profileId == owner.profileId) { "Collection response profile mismatch" }
        val remoteSnapshot = when (val payload = blob?.collectionsJson) {
            null, JsonNull -> JsonArray(emptyList())
            is JsonArray -> payload
            else -> error("Invalid remote collections JSON")
        }
        val journal = journal(owner)
        // A missing row is an unseeded account, not a remote deletion.
        if (blob == null && journal.pending() == null) {
            withContext(Dispatchers.Main) {
                owner.requireCurrent()
                val local = json.parseToJsonElement(CollectionRepository.exportToJson()).jsonArray
                if (local.isNotEmpty()) journal.recordLocal(JsonArray(emptyList()), local)
            }
        }
        val plan = journal.plan(remoteSnapshot)
        val merged = json.decodeFromJsonElement<List<Collection>>(plan.merged)
        val applied = withContext(Dispatchers.Main) {
            owner.requireCurrent()
            if (!journal.commitPull(plan)) return@withContext false
            isSyncingFromRemote = true
            try { CollectionRepository.applyFromRemote(merged, plan.merged) }
            finally { isSyncingFromRemote = false }
            true
        }
        check(applied) { "Collections changed during refresh; pending state retained" }
        val pending = journal.pending() ?: return
        owner.requireCurrent()
        remote.push(owner.profileId, pending.second)
        owner.requireCurrent()
        journal.acknowledge(pending.first, pending.second)
        log.d { "Collection reconciliation completed for profile=${owner.profileId}" }
    }

    fun triggerPush() {
        val owner = accountSyncOwner() ?: return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            try { owner.requireCurrent(); syncMutex.withLock { reconcile(owner) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.w { "Collection upload deferred: ${e::class.simpleName}" } }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeLocalChangesAndPush() {
        observeJob = scope.launch {
            CollectionRepository.localSyncChanges.debounce(1500L).collect { owner ->
                try { owner.requireCurrent(); syncMutex.withLock { reconcile(owner) } }
                catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive() // A stale owner must not kill the observer.
                }
                catch (e: Exception) { log.w { "Collection upload deferred: ${e::class.simpleName}" } }
            }
        }
    }
}
