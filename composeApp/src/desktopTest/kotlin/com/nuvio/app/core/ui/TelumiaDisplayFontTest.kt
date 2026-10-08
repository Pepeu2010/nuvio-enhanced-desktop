package com.nuvio.app.core.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
private val staticFonts = listOf(
    Triple("regular", 400, "2602c71d1f36c96da0022706f15da7d51102d19d10510b77b5a39bf861122807"),
    Triple("medium", 500, "729037f5dc28daa949c5ce8e69d945bf14faf2649db737e6eb085c120c778dd0"),
    Triple("semibold", 600, "2b2ab8c077256d2849465199657f042e36588a7b88d345acd2ab4e5807ebef99"),
    Triple("bold", 700, "f9696798e2d82d52207ac00c4462bc86999d371ab87401db4067feb3def4d27b")
)

private fun tables(bytes: ByteArray): Map<String, Int> {
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    return (0 until (input.getShort(4).toInt() and 0xffff)).associate { index ->
        val entry = 12 + index * 16
        String(bytes, entry, 4, Charsets.US_ASCII) to input.getInt(entry + 8)
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }


class TelumiaDisplayFontTest {
    private fun packaged(name: String): ByteArray =
        javaClass.getResourceAsStream("/composeResources/nuvio.composeapp.generated.resources/font/manrope_$name.ttf")!!.use { it.readBytes() }

    @Test fun realPackagedFontsHaveStaticWeightsAndThePinnedLicensedBytes() {
        for ((name, weight, hash) in staticFonts) {
            val bytes = packaged(name)
            val table = tables(bytes)
            assertFalse("fvar" in table, name)
            assertEquals(weight, ByteBuffer.wrap(bytes).getShort(table.getValue("OS/2") + 4).toInt(), name)
            assertEquals(hash, sha256(bytes), name)
        }
    }

    @Test fun everyDistributedWeightContainsPortuguesePlayerAndProfileGlyphs() {
        for ((name) in staticFonts) {
            val font = Font.createFont(Font.TRUETYPE_FONT, packaged(name).inputStream())
            assertEquals(-1, font.canDisplayUpTo("Áudio • Próximo episódio • Iludida • Legendas • ação • Crianças • 00:01:23 • 1,25×"), name)
        }
    }
}
