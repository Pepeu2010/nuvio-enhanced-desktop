package com.nuvio.app.features.player.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses the packaged JNI bridge and actual bundled libmpv, not a simulated seek callback. */
class NativeTimelineFramesTest {
    @Test fun actualJniExtractsDistinctFramesWithBoundedDimensionsAndReleasesHandles() {
        val directory = Files.createTempDirectory("telumia-frames-á-")
        val fixture = directory.resolve("cenas originais.avi")
        Files.write(fixture, originalAvi())
        val first = NativePlayerBridge.createTimelineWorker(fixture.toString(), emptyArray())
        val second = NativePlayerBridge.createTimelineWorker(fixture.toString(), emptyArray())
        try {
            assertTrue(first != 0L && second != 0L && first != second, "Independent native workers must initialize")
            for ((position, color) in listOf(500L to 0xff0303, 2000L to 0x06ff06, 4500L to 0x0b0bff)) {
                val bytes = assertNotNull(NativePlayerBridge.captureTimelineFrame(second, position), "Native screenshot at $position")
                val frame = assertNotNull(DesktopTimelineFrames.decode(bytes, position, 6000))
                assertEquals(position, frame.decodedPositionMs)
                val image = ImageIO.read(Base64.getDecoder().decode(frame.pngDataUri.substringAfter(',' )).inputStream())
                assertTrue(image.width in 1..320 && image.height in 1..180)
                assertEquals(color, image.getRGB(image.width / 2, image.height / 2) and 0xffffff)
            }
            val firstFrame = assertNotNull(NativePlayerBridge.captureTimelineFrame(first, 500))
            assertEquals(500L, assertNotNull(DesktopTimelineFrames.decode(firstFrame, 500, 6000)).decodedPositionMs)
            NativePlayerBridge.cancelTimelineWorker(second)
            assertNotNull(NativePlayerBridge.captureTimelineFrame(second, 3000))
        } finally {
            NativePlayerBridge.disposeTimelineWorker(first); NativePlayerBridge.disposeTimelineWorker(second)
        }
        assertNull(NativePlayerBridge.captureTimelineFrame(second, 500))
        NativePlayerBridge.cancelTimelineWorker(second); NativePlayerBridge.disposeTimelineWorker(second)
        Files.delete(fixture); Files.delete(directory)
    }

    private fun originalAvi(): ByteArray {
        fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putInt) }.array()
        fun chunk(kind: String, bytes: ByteArray) = kind.toByteArray() + ints(bytes.size) + bytes + if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
        val width = 96; val height = 54; val size = width * height * 3
        val avih = ints(500000, size * 2, 0, 16, 12, 0, 1, size, width, height, 0, 0, 0, 0)
        val strh = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("vidsDIB ".toByteArray()); putInt(0); putShort(0); putShort(0)
            listOf(0,1,2,0,12,size,-1,0).forEach(::putInt)
            putShort(0); putShort(0); putShort(width.toShort()); putShort(height.toShort())
        }.array()
        val strf = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(40); putInt(width); putInt(height); putShort(1); putShort(24)
            listOf(0,size,0,0,0,0).forEach(::putInt)
        }.array()
        val headers = chunk("LIST", "hdrl".toByteArray() + chunk("avih", avih) + chunk("LIST", "strl".toByteArray() + chunk("strh", strh) + chunk("strf", strf)))
        var frames = byteArrayOf(); var index = byteArrayOf(); var offset = 4
        for (frame in 0 until 12) {
            val colors = ByteArray(3) { (frame + 2).toByte() }; colors[2 - frame / 4] = -1
            val pixels = ByteArray(size) { colors[it % 3] }; val encoded = chunk("00db", pixels)
            frames += encoded; index += "00db".toByteArray() + ints(16, offset, size); offset += encoded.size
        }
        return chunk("RIFF", "AVI ".toByteArray() + headers + chunk("LIST", "movi".toByteArray() + frames) + chunk("idx1", index))
    }
}
