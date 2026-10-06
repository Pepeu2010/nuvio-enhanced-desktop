package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.IOException

class DesktopMediaCachePreferencesTest {
    @Test fun failedPreferenceReplacementRestoresMemoryAndCanBeRetried() {
        val directory = Files.createTempDirectory("telumia-preference-failure")
        val file = directory.resolve("preferences.properties")
        val store = DesktopStorage.Store(file)
        store.putString("cache", "old")
        val backup = directory.resolve("backup.properties")
        Files.move(file, backup)
        Files.createDirectory(file)
        val blocker = file.resolve("owned-test-blocker")
        Files.writeString(blocker, "block")
        assertFailsWith<IOException> { store.putString("cache", "new") }
        assertEquals("old", store.getString("cache"))
        assertEquals("old", DesktopStorage.Store(backup).getString("cache"))
        assertTrue(Files.list(directory).use { stream -> stream.noneMatch { it.fileName.toString().endsWith(".part") } })
        Files.delete(blocker)
        Files.delete(file)
        Files.move(backup, file)
        store.putString("cache", "new")
        assertEquals("new", DesktopStorage.Store(file).getString("cache"))
    }
    @Test fun manualChoicePersistsAcrossStoreRecreationAndAutoKeepsTheChosenBudget() {
        val file = Files.createTempDirectory("telumia-cache-preferences").resolve("preferences.properties")
        val original = DesktopMediaCachePreferences(DesktopStorage.Store(file))
        val manual = MediaCacheSettings(MediaCacheMode.MANUAL, 2048L * MIB)
        original.save(manual)
        assertEquals(manual, DesktopMediaCachePreferences(DesktopStorage.Store(file)).load())
        original.save(manual.copy(mode = MediaCacheMode.AUTO))
        assertEquals(manual.copy(mode = MediaCacheMode.AUTO), DesktopMediaCachePreferences(DesktopStorage.Store(file)).load())
    }

    @Test fun malformedOrFuturePreferencesFallBackWithoutBreakingStartup() {
        val file = Files.createTempDirectory("telumia-cache-invalid").resolve("preferences.properties")
        val store = DesktopStorage.Store(file)
        val preferences = DesktopMediaCachePreferences(store)
        for (raw in listOf("{", "[]", "{\"schemaVersion\":999,\"mode\":\"MANUAL\"}", "{\"schemaVersion\":1,\"mode\":\"INVALID\"}")) {
            store.putString("device_cache_policy_v1", raw)
            assertEquals(MediaCacheSettings(), preferences.load())
        }
    }
}
