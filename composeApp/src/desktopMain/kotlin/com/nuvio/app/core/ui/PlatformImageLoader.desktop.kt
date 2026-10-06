package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import com.nuvio.app.core.storage.DesktopMediaCache
import com.nuvio.app.core.storage.MediaCacheCategory
import okio.Path.Companion.toOkioPath

internal actual val platformProvidesImageLoader: Boolean = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    components { add(SkiaGifDecoder.Factory()) }
        .diskCachePolicy(if (DesktopMediaCache.writesEnabled) CachePolicy.ENABLED else CachePolicy.READ_ONLY)
        .diskCache {
        val quota = DesktopMediaCache.coilImageQuota()
        if (quota <= 0L) null else DiskCache.Builder()
            .directory(DesktopMediaCache.directory(MediaCacheCategory.IMAGES).resolve("coil").toFile().toOkioPath())
            .maxSizeBytes(quota).build()
    }

internal actual fun ImageLoader.Builder.configurePlatformBadgeImageLoader(): ImageLoader.Builder =
    diskCachePolicy(if (DesktopMediaCache.writesEnabled) CachePolicy.ENABLED else CachePolicy.READ_ONLY).diskCache {
    val quota = DesktopMediaCache.coilBadgeQuota()
    if (quota <= 0L) null else DiskCache.Builder()
        .directory(DesktopMediaCache.directory(MediaCacheCategory.BADGES).resolve("coil").toFile().toOkioPath())
        .maxSizeBytes(quota).build()
}
