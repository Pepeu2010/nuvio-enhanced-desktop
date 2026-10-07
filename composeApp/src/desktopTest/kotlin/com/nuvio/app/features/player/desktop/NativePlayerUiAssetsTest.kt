package com.nuvio.app.features.player.desktop

import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePlayerUiAssetsTest {
    @Test fun exportedControlsIncludeTheirRealFontAndLicenseWithoutLoadingTheNativePlayer() {
        val files = NativePlayerUiAssets.files()
        val css = files.getValue("controls.css").toString(Charsets.UTF_8)
        assertFalse(css.contains("__TELUMIA_PLAYER_FONT_FACES__"))
        assertFalse(css.contains("Nuvio JetBrains Sans"))
        assertTrue(css.contains("font-weight: 200 800"))
        assertTrue(css.contains("fonts/manrope_variable.ttf"))
        assertTrue(files.getValue("fonts/OFL.txt").toString(Charsets.UTF_8).contains("SIL OPEN FONT LICENSE"))
        for (path in listOf("controls.html", "controls.js", "timed-metadata.js")) {
            val packaged = javaClass.getResourceAsStream("/player-ui/$path")!!.use { it.readBytes() }
            assertTrue(packaged.contentEquals(files.getValue(path)), "Native control behavior changed while exporting $path")
        }
        // Optional controlled export lets the browser gate inspect the same asset map used by the JNI bridge.
        System.getenv("TELUMIA_PLAYER_ASSET_EXPORT_DIR")?.let { target ->
            val directory = Path.of(target).toAbsolutePath().normalize()
            require(directory.fileName.toString() == "telumia-player-assets")
            for ((relative, bytes) in files) {
                val file = directory.resolve(relative).normalize()
                require(file.startsWith(directory))
                Files.createDirectories(file.parent)
                Files.write(file, bytes)
            }
        }
    }

    @Test fun theDistributedFontDecodesAndContainsPortugueseLettersAndPlayerNumbers() {
        val bytes = NativePlayerUiAssets.files().getValue("fonts/manrope_variable.ttf")
        val font = Font.createFont(Font.TRUETYPE_FONT, bytes.inputStream())
        assertTrue(font.family.contains("Manrope", ignoreCase = true))
        assertEquals(-1, font.canDisplayUpTo("Áudio • Próximo episódio • Iludida • Legendas • ação • 00:01:23 • 1,25×"))
    }
}
