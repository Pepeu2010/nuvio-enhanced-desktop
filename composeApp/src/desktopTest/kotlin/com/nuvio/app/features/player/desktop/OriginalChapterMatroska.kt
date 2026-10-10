package com.nuvio.app.features.player.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/** Original generated JPEG frames and chapter text. No downloaded video or external encoder. */
internal fun originalChapterMatroska(): ByteArray {
    fun unsigned(value: Long): ByteArray {
        val bytes = ByteBuffer.allocate(8).putLong(value).array().dropWhile { it == 0.toByte() }.toByteArray()
        return if (bytes.isEmpty()) byteArrayOf(0) else bytes
    }
    fun size(value: Int): ByteArray {
        var width = 1
        while (value.toLong() >= (1L shl (7 * width)) - 1) width++
        val encoded = value.toLong() or (1L shl (7 * width))
        return ByteArray(width) { ((encoded ushr (8 * (width - it - 1))) and 255).toByte() }
    }
    fun element(id: Int, bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(4).putInt(id).array().dropWhile { it == 0.toByte() }.toByteArray() + size(bytes.size) + bytes
    fun number(id: Int, value: Long) = element(id, unsigned(value))
    fun text(id: Int, value: String) = element(id, value.encodeToByteArray())
    val header = element(0x1A45DFA3, number(0x4286, 1) + number(0x42F7, 1) + number(0x42F2, 4) +
        number(0x42F3, 8) + text(0x4282, "matroska") + number(0x4287, 4) + number(0x4285, 2))
    val info = element(0x1549A966, number(0x2AD7B1, 1_000_000) +
        element(0x4489, ByteBuffer.allocate(8).putDouble(6000.0).array()) +
        text(0x4D80, "Telumia original fixture") + text(0x5741, "Telumia original fixture"))
    val video = element(0xE0, number(0xB0, 96) + number(0xBA, 54))
    val tracks = element(0x1654AE6B, element(0xAE, number(0xD7, 1) + number(0x73C5, 1) +
        number(0x83, 1) + text(0x86, "V_MJPEG") + number(0x23E383, 500_000_000) + video))
    fun chapter(uid: Long, start: Long, end: Long, title: String) = element(0xB6,
        number(0x73C4, uid) + number(0x91, start) + number(0x92, end) +
            element(0x80, text(0x85, title) + text(0x437C, "por")))
    val chapters = element(0x1043A770, element(0x45B9, number(0x45DB, 1) +
        chapter(1, 0, 3_000_000_000, "Introdução \"Café\"\nlocal") +
        chapter(2, 3_000_000_000, 6_000_000_000, "Final 😀")))
    var blocks = number(0xE7, 0)
    repeat(12) { index ->
        val image = BufferedImage(96, 54, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = if (index < 6) Color(220, 30, 30) else Color(30, 30, 220)
            graphics.fillRect(0, 0, 96, 54)
        } finally { graphics.dispose() }
        val jpeg = ByteArrayOutputStream().also { check(ImageIO.write(image, "jpeg", it)) }.toByteArray()
        val prefix = ByteBuffer.allocate(4).put(0x81.toByte()).putShort((index * 500).toShort()).put(0x80.toByte()).array()
        blocks += element(0xA3, prefix + jpeg)
    }
    return header + element(0x18538067, info + tracks + chapters + element(0x1F43B675, blocks))
}
