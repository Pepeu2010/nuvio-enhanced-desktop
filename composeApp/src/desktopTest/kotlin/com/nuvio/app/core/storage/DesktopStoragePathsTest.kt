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
        assertEquals(Path.of("roaming/NuvioEnhanced"), data)
        assertEquals(Path.of("local/NuvioEnhanced/Cache"), cache)
        assertFalse(data.startsWith(Path.of("roaming/Nuvio")))
        assertFalse(cache.startsWith(Path.of("local/Nuvio")))
    }

    @Test
    fun blankWindowsEnvironmentUsesIsolatedFallbacks() {
        assertEquals(home.resolve("AppData/Roaming/NuvioEnhanced"), DesktopStoragePaths.data("Windows", home) { " " })
        assertEquals(home.resolve("AppData/Local/NuvioEnhanced/Cache"), DesktopStoragePaths.cache("Windows", home) { null })
    }

    @Test
    fun macUsesIsolatedApplicationSupportAndCache() {
        assertEquals(home.resolve("Library/Application Support/NuvioEnhanced"), DesktopStoragePaths.data("Mac OS X", home) { null })
        assertEquals(home.resolve("Library/Caches/NuvioEnhanced"), DesktopStoragePaths.cache("Mac OS X", home) { null })
    }

    @Test
    fun linuxHonorsXdgWithoutUsingOfficialRoots() {
        val env = mapOf("XDG_CONFIG_HOME" to "config", "XDG_CACHE_HOME" to "cache")
        assertEquals(Path.of("config/nuvio-enhanced"), DesktopStoragePaths.data("Linux", home, env::get))
        assertEquals(Path.of("cache/nuvio-enhanced"), DesktopStoragePaths.cache("Linux", home, env::get))
        assertEquals(home.resolve(".config/nuvio-enhanced"), DesktopStoragePaths.data("Linux", home) { null })
        assertEquals(home.resolve(".cache/nuvio-enhanced"), DesktopStoragePaths.cache("Linux", home) { "" })
    }
}
