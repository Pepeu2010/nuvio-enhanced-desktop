package com.nuvio.app.core.storage

import kotlinx.serialization.json.*

internal class DesktopMediaCachePreferences(private val store: DesktopStorage.Store) {
    private val key = "device_cache_policy_v1"
    fun load(): MediaCacheSettings = runCatching {
        val payload = store.getString(key)?.let { Json.parseToJsonElement(it).jsonObject } ?: return MediaCacheSettings()
        if (payload["schemaVersion"]?.jsonPrimitive?.intOrNull != 1) return MediaCacheSettings()
        MediaCacheSettings(MediaCacheMode.valueOf(payload.getValue("mode").jsonPrimitive.content),
            payload["manualBytes"]?.jsonPrimitive?.longOrNull?.coerceIn(32L * MIB, MediaCachePolicy.MAX_CONFIGURED_BYTES))
    }.getOrDefault(MediaCacheSettings())

    fun save(settings: MediaCacheSettings) {
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            put("mode", settings.mode.name)
            put("manualBytes", (settings.manualBytes ?: MediaCachePlatform.DESKTOP.initialBytes)
                .coerceIn(32L * MIB, MediaCachePolicy.MAX_CONFIGURED_BYTES))
        }
        store.putString(key, payload.toString())
    }
}
