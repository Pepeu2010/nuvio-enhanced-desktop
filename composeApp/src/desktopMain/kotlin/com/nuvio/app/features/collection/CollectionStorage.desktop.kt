package com.nuvio.app.features.collection

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object CollectionStorage {
    private val store = DesktopStorage.store("nuvio_collections")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("collections"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("collections"), payload)
    }
    actual fun loadSyncJournal(ownerKey: String): String? = store.getString("sync-v1-$ownerKey")
    actual fun saveSyncJournal(ownerKey: String, payload: String) { store.putString("sync-v1-$ownerKey", payload) }
}
