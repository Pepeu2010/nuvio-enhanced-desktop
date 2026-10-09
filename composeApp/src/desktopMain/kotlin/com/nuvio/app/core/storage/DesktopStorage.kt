package com.nuvio.app.core.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.AccessDeniedException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Comparator
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir().also { Files.createDirectories(it) }
    }

    val cacheDir: Path by lazy {
        resolveCacheDir().also { Files.createDirectories(it) }
    }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

    fun wipe() {
        synchronized(stores) {
            stores.values.forEach(Store::clearInMemory)
            stores.clear()
        }
        if (!rootDir.exists()) return
        Files.walk(rootDir).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .filter { it != rootDir }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun resolveAppDataDir(): Path = DesktopStoragePaths.data(
        System.getProperty("os.name").orEmpty(),
        Paths.get(System.getProperty("user.home").orEmpty()),
        System::getenv,
    )

    private fun resolveCacheDir(): Path = DesktopStoragePaths.cache(
        System.getProperty("os.name").orEmpty(),
        Paths.get(System.getProperty("user.home").orEmpty()),
        System::getenv,
    )

    internal class Store(
        private val file: Path,
        private val publish: (Path, Path) -> Unit = ::publishDesktopPreferenceFile,
    ) {
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            val previous = properties.getProperty(key)
            val changed = if (value == null) {
                properties.remove(key) != null
            } else {
                properties.setProperty(key, value) != value
            }
            if (changed) {
                try { persist() } catch (error: Exception) {
                    if (previous == null) properties.remove(key) else properties.setProperty(key, previous)
                    throw error
                }
            }
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = putString(key, null)

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            val removed = keys.mapNotNull { key -> properties.remove(key)?.let { key to it } }.toMap()
            if (removed.isNotEmpty()) {
                try { persist() } catch (error: Exception) {
                    properties.putAll(removed)
                    throw error
                }
            }
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
        }

        private fun ensureLoaded() {
            if (loaded) return
            val restored = Properties()
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                Files.newInputStream(file).use { input ->
                    restored.load(input)
                }
            }
            properties.clear()
            properties.putAll(restored)
            loaded = true
        }

        private fun persist() {
            Files.createDirectories(file.parent)
            val pending = Files.createTempFile(file.parent, "telumia-preferences-", ".part")
            try {
                Files.newOutputStream(pending).use { output -> properties.store(output, "Telumia desktop preferences") }
                publish(pending, file)
            } finally {
                Files.deleteIfExists(pending)
            }
        }
    }
}

/** A Windows reader can briefly deny replacement. Keep the old file and bound the retry to 140 ms. */
internal fun publishDesktopPreferenceFile(
    pending: Path,
    destination: Path,
    move: (Path, Path, Boolean) -> Unit = { source, target, atomic ->
        if (atomic) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    },
    pause: (Long) -> Unit = { Thread.sleep(it) },
    retryAccessDenied: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
) {
    var atomic = true
    var retries = 0
    while (true) {
        try { move(pending, destination, atomic); return }
        catch (unsupported: AtomicMoveNotSupportedException) {
            if (!atomic) throw unsupported
            atomic = false
        } catch (denied: AccessDeniedException) {
            if (!retryAccessDenied || retries >= 3 || !Files.isRegularFile(pending, NOFOLLOW_LINKS)) throw denied
            pause(20L shl retries++)
        }
    }
}
