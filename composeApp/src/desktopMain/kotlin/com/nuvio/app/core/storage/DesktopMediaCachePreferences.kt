package com.nuvio.app.core.storage

import kotlinx.serialization.json.*
import java.io.IOException

internal class DesktopMediaCachePreferences(private val store: DesktopStorage.Store) {
    private val key = "device_cache_policy_v1"
    fun load(): MediaCacheSettings = runCatching {
        val payload = store.getString(key)?.let { Json.parseToJsonElement(it).jsonObject } ?: return MediaCacheSettings()
        if (payload["schemaVersion"]?.jsonPrimitive?.intOrNull != 1) return MediaCacheSettings()
        MediaCacheSettings(MediaCacheMode.valueOf(payload.getValue("mode").jsonPrimitive.content),
            payload["manualBytes"]?.jsonPrimitive?.longOrNull?.coerceIn(32L * MIB, MediaCachePolicy.MAX_CONFIGURED_BYTES))
    }.getOrDefault(MediaCacheSettings())

    @Synchronized fun save(settings: MediaCacheSettings) {
        // Older clients may read defaults, but must never downgrade an unknown document.
        store.getString(key)?.let { raw ->
            val version = runCatching { Json.parseToJsonElement(raw).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull }.getOrNull()
            if (version != 1) throw IOException("Unsupported cache settings version")
        }
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            put("mode", settings.mode.name)
            put("manualBytes", (settings.manualBytes ?: MediaCachePlatform.DESKTOP.initialBytes)
                .coerceIn(32L * MIB, MediaCachePolicy.MAX_CONFIGURED_BYTES))
        }
        store.putString(key, payload.toString())
    }
}
