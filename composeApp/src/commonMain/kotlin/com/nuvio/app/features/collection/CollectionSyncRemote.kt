package com.nuvio.app.features.collection

import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface CollectionSyncRemote {
    suspend fun pull(profileId: Int): SupabaseCollectionBlob?
    suspend fun push(profileId: Int, payload: JsonArray)
}

internal object SupabaseCollectionSyncRemote : CollectionSyncRemote {
    override suspend fun pull(profileId: Int): SupabaseCollectionBlob? =
        SupabaseProvider.client.postgrest.rpc("sync_pull_collections", buildJsonObject {
            put("p_profile_id", profileId)
        }).decodeList<SupabaseCollectionBlob>().firstOrNull()

    override suspend fun push(profileId: Int, payload: JsonArray) {
        SupabaseProvider.client.postgrest.rpc("sync_push_collections", buildJsonObject {
            put("p_profile_id", profileId)
            put("p_collections_json", payload)
            putSyncOriginClientId()
        })
    }
}
