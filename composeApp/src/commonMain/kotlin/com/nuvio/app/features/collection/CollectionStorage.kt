package com.nuvio.app.features.collection

internal expect object CollectionStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun loadSyncJournal(ownerKey: String): String?
    fun saveSyncJournal(ownerKey: String, payload: String)
}
