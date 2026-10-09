package com.nuvio.app.features.collection

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

actual object CollectionStorage {
    private const val payloadKey = "collections_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }
    actual fun loadSyncJournal(ownerKey: String): String? = NSUserDefaults.standardUserDefaults.stringForKey("collections-sync-v1-$ownerKey")
    actual fun saveSyncJournal(ownerKey: String, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = "collections-sync-v1-$ownerKey")
    }
}
