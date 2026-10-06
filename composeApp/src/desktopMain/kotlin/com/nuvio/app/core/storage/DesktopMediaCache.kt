package com.nuvio.app.core.storage

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import com.sun.management.OperatingSystemMXBean

/** Device-local policy; no auth/profile sync payload is extended. Native runtime files are outside its root. */
internal object DesktopMediaCache {
    private val preferences = DesktopMediaCachePreferences(DesktopStorage.store("telumia_media_cache_v1"))
    val root: Path by lazy { DesktopStorage.cacheDir.resolve("media-cache-v1").also { Files.createDirectories(it) } }
    val activeSettings: MediaCacheSettings by lazy { loadSettings() }
    val activeBudget: MediaCacheBudget by lazy { MediaCachePolicy.resolve(MediaCachePlatform.DESKTOP, activeSettings, detectDevice()) }

    fun loadSettings(): MediaCacheSettings = preferences.load()
    fun saveSettings(settings: MediaCacheSettings) = preferences.save(settings)

    fun detectDevice(): MediaCacheDeviceSnapshot = MediaCacheDeviceSnapshot(
        memoryBytes = runCatching { (ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)?.totalMemorySize }.getOrNull(),
        usableStorageBytes = runCatching { Files.getFileStore(root).usableSpace }.getOrNull())

    fun directory(category: MediaCacheCategory): Path = root.resolve(category.directoryName)
    val writesEnabled: Boolean get() = activeBudget.totalBytes > 0
    // Under the storage reserve, open existing Coil caches for reads with their requested capacity.
    // The loader's READ_ONLY policy suspends writes rather than deleting offline records.
    private fun coilBudget(): MediaCacheBudget = if (writesEnabled) activeBudget else activeBudget.copy(totalBytes = activeBudget.requestedBytes)
    fun coilImageQuota(): Long = coilBudget().quota(MediaCacheCategory.IMAGES) * 9 / 10
    fun coilBadgeQuota(): Long = coilBudget().quota(MediaCacheCategory.BADGES)

    val gifCache: BoundedFileCache by lazy { BoundedFileCache(directory(MediaCacheCategory.IMAGES).resolve("gifs"),
        quotaBytes = { activeBudget.quota(MediaCacheCategory.IMAGES) / 10 }, maxFiles = 200, canWrite = ::hasStorageFor) }

    /** Migrate legacy GIFs on demand; no network request is required for an already cached image. */
    fun readGif(url: String): ByteArray? {
        gifCache.get(url)?.let { return it }
        return runCatching {
            val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val legacy = DesktopStorage.cacheDir.resolve("gif-cache").resolve("$hash.gif").normalize()
            if (!Files.isRegularFile(legacy, NOFOLLOW_LINKS) || Files.size(legacy) > BoundedFileCache.MAX_ENTRY_BYTES ||
                !legacy.toRealPath().startsWith(DesktopStorage.cacheDir.toRealPath())) return@runCatching null
            Files.readAllBytes(legacy).also { bytes -> if (gifCache.put(url, bytes)) Files.deleteIfExists(legacy) }
        }.getOrNull()
    }

    private val fileCaches = mutableMapOf<MediaCacheCategory, BoundedFileCache>()
    fun files(category: MediaCacheCategory): BoundedFileCache = synchronized(fileCaches) {
        require(category != MediaCacheCategory.IMAGES && category != MediaCacheCategory.BADGES)
        fileCaches.getOrPut(category) { BoundedFileCache(directory(category).resolve("objects"),
            quotaBytes = { activeBudget.quota(category) }, canWrite = ::hasStorageFor) }
    }

    private fun hasStorageFor(bytes: Long): Boolean = runCatching {
        Files.getFileStore(root).usableSpace - bytes >= MediaCachePlatform.DESKTOP.reserveBytes
    }.getOrDefault(false)
}
