package com.nuvio.app.features.addons

import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class AddonSyncRow(val url: String, val name: String? = null, val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0)

internal interface AddonSyncRemote {
    suspend fun pull(profileId: Int): JsonArray
    suspend fun push(profileId: Int, snapshot: JsonArray)
}

internal object SupabaseAddonSyncRemote : AddonSyncRemote {
    override suspend fun pull(profileId: Int): JsonArray {
        val rows = SupabaseProvider.client.postgrest.from("addons").select {
            filter { eq("profile_id", profileId) }
            order("sort_order", Order.ASCENDING)
        }.decodeList<AddonSyncRow>()
        // Keep the existing push contract; account/row IDs and server columns are not echoed.
        return JsonArray(rows.map { row -> buildJsonObject {
            put("url", ensureManifestSuffix(row.url)); put("name", row.name.orEmpty())
            put("enabled", row.enabled); put("sort_order", row.sortOrder)
        } })
    }
    override suspend fun push(profileId: Int, snapshot: JsonArray) {
        SupabaseProvider.client.postgrest.rpc("sync_push_addons", buildJsonObject {
            put("p_profile_id", profileId); put("p_addons", snapshot); putSyncOriginClientId()
        })
    }
}
