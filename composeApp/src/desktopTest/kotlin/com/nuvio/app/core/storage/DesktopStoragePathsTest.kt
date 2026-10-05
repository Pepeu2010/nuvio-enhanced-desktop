package com.nuvio.app.core.storage

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopStoragePathsTest {
    private val home = Path.of("test-home")

    @Test
    fun windowsUsesDistinctRoamingAndLocalRoots() {
        val env = mapOf("APPDATA" to "roaming", "LOCALAPPDATA" to "local")
        val data = DesktopStoragePaths.data("Windows 11", home, env::get)
        val cache = DesktopStoragePaths.cache("Windows 11", home, env::get)
        assertEquals(Path.of("roaming/Telumia"), data)
        assertEquals(Path.of("local/Telumia/Cache"), cache)
        assertFalse(data.startsWith(Path.of("roaming/Nuvio")))
        assertFalse(cache.startsWith(Path.of("local/Nuvio")))
    }

    @Test
    fun blankWindowsEnvironmentUsesIsolatedFallbacks() {
        assertEquals(home.resolve("AppData/Roaming/Telumia"), DesktopStoragePaths.data("Windows", home) { " " })
        assertEquals(home.resolve("AppData/Local/Telumia/Cache"), DesktopStoragePaths.cache("Windows", home) { null })
    }

    @Test
    fun macUsesIsolatedApplicationSupportAndCache() {
        assertEquals(home.resolve("Library/Application Support/Telumia"), DesktopStoragePaths.data("Mac OS X", home) { null })
        assertEquals(home.resolve("Library/Caches/Telumia"), DesktopStoragePaths.cache("Mac OS X", home) { null })
    }

    @Test
    fun linuxHonorsXdgWithoutUsingOfficialRoots() {
        val env = mapOf("XDG_CONFIG_HOME" to "config", "XDG_CACHE_HOME" to "cache")
        assertEquals(Path.of("config/telumia"), DesktopStoragePaths.data("Linux", home, env::get))
        assertEquals(Path.of("cache/telumia"), DesktopStoragePaths.cache("Linux", home, env::get))
        assertEquals(home.resolve(".config/telumia"), DesktopStoragePaths.data("Linux", home) { null })
        assertEquals(home.resolve(".cache/telumia"), DesktopStoragePaths.cache("Linux", home) { "" })
    }
}
