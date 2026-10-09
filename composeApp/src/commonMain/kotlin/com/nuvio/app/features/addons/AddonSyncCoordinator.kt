package com.nuvio.app.features.addons

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.sync.AccountSyncOwner
import com.nuvio.app.core.sync.SnapshotSyncJournal
import com.nuvio.app.core.sync.accountSyncOwner
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal object AddonSyncCoordinator {
    private val lock = SynchronizedObject()
    private val syncMutex = Mutex()
    internal var remote: AddonSyncRemote = SupabaseAddonSyncRemote

    private fun journal(owner: AccountSyncOwner, effectiveProfileId: Int): SnapshotSyncJournal {
        val ownerKey = JsonArray(listOf(JsonPrimitive(owner.backendUrl), JsonPrimitive(owner.userId), JsonPrimitive(effectiveProfileId)))
            .toString().encodeToByteArray().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return SnapshotSyncJournal({ AddonStorage.loadSyncJournal(ownerKey) }, { payload ->
            check(owner.matches(AuthRepository.state.value, ProfileRepository.activeProfileId)) { "Addon journal owner changed" }
            AddonStorage.saveSyncJournal(ownerKey, payload)
        }, "url")
    }

    fun recordLocal(effectiveProfileId: Int, previous: JsonArray, next: JsonArray) {
        val owner = accountSyncOwner() ?: return
        if (previous != next) synchronized(lock) { journal(owner, effectiveProfileId).recordLocal(previous, next) }
    }

    fun recover(effectiveProfileId: Int, fallback: JsonArray): JsonArray {
        val owner = accountSyncOwner() ?: return fallback
        return runCatching { synchronized(lock) { journal(owner, effectiveProfileId).pending()?.second ?: fallback } }
            .getOrDefault(fallback) // Unsupported journals remain untouched; the next sync reports the failure.
    }

    suspend fun reconcile(owner: AccountSyncOwner, effectiveProfileId: Int, mayUpload: Boolean,
        stillEffective: () -> Boolean, apply: (JsonArray) -> Unit) = syncMutex.withLock {
        owner.requireCurrent()
        check(stillEffective()) { "Addon profile scope changed" }
        val server = normalizedAddonSnapshot(remote.pull(effectiveProfileId))
        owner.requireCurrent()
        check(stillEffective()) { "Addon profile scope changed" }
        val journal = journal(owner, effectiveProfileId)
        val plan = synchronized(lock) { journal.plan(server) }
        withContext(Dispatchers.Main) {
            owner.requireCurrent()
            check(stillEffective()) { "Addon profile scope changed" }
            synchronized(lock) {
                check(journal.commitPull(plan)) { "Addons changed during refresh; pending state retained" }
                apply(normalizedAddonSnapshot(plan.merged))
            }
        }
        if (mayUpload) {
            val pending = synchronized(lock) { journal.pending() }
            if (pending != null) {
                owner.requireCurrent()
                check(stillEffective()) { "Addon profile scope changed" }
                val uploaded = normalizedAddonSnapshot(pending.second)
                remote.push(effectiveProfileId, uploaded)
                owner.requireCurrent()
                check(stillEffective()) { "Addon profile scope changed" }
                synchronized(lock) { journal.acknowledge(pending.first, uploaded) }
            }
        }
    }
}

/** Array order is authoritative; sort_order is recomputed after independent additions. */
internal fun normalizedAddonSnapshot(snapshot: JsonArray): JsonArray {
    require(snapshot.size <= 4096)
    val seen = mutableSetOf<String>()
    return JsonArray(snapshot.mapIndexed { index, item ->
        val row = item as? JsonObject ?: error("Invalid addon snapshot")
        val url = (row["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid addon identity")
        require(url.isNotBlank()) { "Invalid addon identity" }
        val canonical = ensureManifestSuffix(url)
        require(canonical.isNotBlank() && canonical.length <= 16_384 && seen.add(canonical)) { "Invalid addon identity" }
        val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val enabled = row["enabled"]?.jsonPrimitive?.boolean ?: true
        buildJsonObject { put("url", canonical); put("name", name); put("enabled", enabled); put("sort_order", index) }
    })
}
