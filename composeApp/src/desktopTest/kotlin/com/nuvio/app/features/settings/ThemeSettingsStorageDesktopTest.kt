package com.nuvio.app.features.settings

import java.util.Locale
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeSettingsStorageDesktopTest {
    @Test
    fun `local motion persists to the existing store and survives a sync replacement`() {
        // This integration test must never write to the user's application data.
        assumeTrue(System.getenv("NUVIO_ENHANCED_ISOLATED_THEME_TEST") == "1")
        val sandbox = Paths.get(System.getenv("APPDATA")).resolve("NuvioEnhanced")
        assertTrue(DesktopStorage.rootDir.startsWith(sandbox))
        ThemeSettingsStorage.saveNavigationMotion(NavigationMotion.OFF.name)
        assertFalse("enhanced_navigation_motion" in ThemeSettingsStorage.exportToSyncPayload())
        ThemeSettingsStorage.replaceFromSyncPayload(kotlinx.serialization.json.buildJsonObject {})
        assertEquals("OFF", ThemeSettingsStorage.loadNavigationMotion())
        val disk = Properties()
        Files.newInputStream(sandbox.resolve("nuvio_theme_settings.properties")).use(disk::load)
        assertEquals("OFF", disk.getProperty("enhanced_navigation_motion_1"))
    }

    @Test
    fun `reduced navigation removes spatial motion and caps fades without losing instant states`() {
        assertEquals(false, NavigationMotion.REDUCED.allowsSpatialEffects)
        assertEquals(120, NavigationMotion.REDUCED.durationMillis(700))
        assertEquals(80, NavigationMotion.REDUCED.durationMillis(80))
        assertEquals(0, NavigationMotion.REDUCED.durationMillis(0))
        assertEquals(0, NavigationMotion.OFF.durationMillis(700))
        assertEquals(false, NavigationMotion.OFF.allowsSpatialEffects)
        assertEquals(220, NavigationMotion.FULL.durationMillis(220))
        assertEquals(0, NavigationMotion.FULL.durationMillis(-1))
    }

    @Test
    fun `old and corrupt preferences preserve default navigation while explicit off survives decoding`() {
        assertEquals(NavigationMotion.FULL, NavigationMotion.fromName(null))
        assertEquals(NavigationMotion.FULL, NavigationMotion.fromName("future-mode"))
        assertEquals(NavigationMotion.OFF, NavigationMotion.fromName("OFF"))
        assertEquals(NavigationMotion.REDUCED, NavigationMotion.fromName("REDUCED"))
    }

    @Test
    fun `device language resolves to captured system locale`() {
        val systemLocale = Locale.forLanguageTag("en-US")

        assertEquals(systemLocale, resolveDesktopAppLocale(AppLanguage.DEVICE.code, systemLocale))
    }

    @Test
    fun `explicit language resolves from its language tag`() {
        val systemLocale = Locale.forLanguageTag("en-US")

        assertEquals(
            Locale.forLanguageTag("pt-BR"),
            resolveDesktopAppLocale("pt-BR", systemLocale),
        )
    }
}
