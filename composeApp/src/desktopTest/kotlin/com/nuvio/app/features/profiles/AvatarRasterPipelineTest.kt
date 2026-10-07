package com.nuvio.app.features.profiles

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class AvatarRasterPipelineTest {
    private fun imageBytes(format: String = "png"): ByteArray {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.RED; graphics.fillRect(0, 0, 100, 100)
            graphics.color = Color.BLUE; graphics.fillRect(100, 0, 100, 100)
        } finally { graphics.dispose() }
        return ByteArrayOutputStream().use { ImageIO.write(image, format, it); it.toByteArray() }
    }

    @Test fun squareVariantsFollowTheSelectedRegionAndHaveOptimizedSizes() {
        val source = AvatarRasterPipeline.decode(imageBytes())
        val left = AvatarRasterPipeline.variants(source, AvatarCrop(centerX = 0f))
        val right = AvatarRasterPipeline.variants(source, AvatarCrop(centerX = 1f, zoom = 2f))
        assertEquals(setOf(64, 128, 256, 512), left.keys)
        for (size in left.keys) {
            val decodedLeft = ImageIO.read(ByteArrayInputStream(left.getValue(size)))
            val decodedRight = ImageIO.read(ByteArrayInputStream(right.getValue(size)))
            assertEquals(size, decodedLeft.width); assertEquals(size, decodedLeft.height)
            assertEquals(Color.RED.rgb, decodedLeft.getRGB(size / 2, size / 2))
            assertEquals(Color.BLUE.rgb, decodedRight.getRGB(size / 2, size / 2))
            assertTrue(left.getValue(size).size < 64 * 1024)
        }
    }

    @Test fun oversizedEncodedFilesAndActiveVectorContentAreRejected() {
        assertEquals(AvatarImageFailure.TOO_LARGE, assertFailsWith<AvatarImageException> {
            AvatarRasterPipeline.decode(ByteArray(AvatarRasterPipeline.MAX_INPUT_BYTES + 1)) }.reason)
        assertEquals(AvatarImageFailure.UNSUPPORTED, assertFailsWith<AvatarImageException> {
            AvatarRasterPipeline.decode("<svg onload='alert(1)'/>".toByteArray()) }.reason)
        assertEquals(AvatarImageFailure.INVALID, assertFailsWith<AvatarImageException> {
            AvatarRasterPipeline.variants(AvatarRasterPipeline.decode(imageBytes()), AvatarCrop(zoom = Float.NaN)) }.reason)
        val header = imageBytes().copyOf()
        java.nio.ByteBuffer.wrap(header).putInt(16, 8192).putInt(20, 8192)
        val checksum = java.util.zip.CRC32().apply { update(header, 12, 17) }.value.toInt()
        java.nio.ByteBuffer.wrap(header).putInt(29, checksum)
        assertEquals(AvatarImageFailure.TOO_LARGE, assertFailsWith<AvatarImageException> { AvatarRasterPipeline.decode(header) }.reason)
    }

    @Test fun filesystemImportDoesNotFollowSymlinksOrRelyOnFilenameExtension() {
        val directory = Files.createTempDirectory("telumia-avatar-import")
        val file = directory.resolve("renamed.data")
        Files.write(file, imageBytes("jpeg"))
        val source = AvatarRasterPipeline.readFile(file)
        assertEquals(200, source.width); assertEquals(100, source.height)
        assertFailsWith<AvatarImageException> { AvatarRasterPipeline.readFile(directory) }
        // Native symlink creation may require Windows privileges; the directory refusal is always exercised.
        val link = directory.resolve("link.png")
        if (runCatching { Files.createSymbolicLink(link, file) }.isSuccess) {
            assertFailsWith<AvatarImageException> { AvatarRasterPipeline.readFile(link) }
        }
    }

    @Test fun jpegExifOrientationIsAppliedBeforeCroppingAndNotCopiedToOutput() {
        val jpeg = imageBytes("jpeg")
        val payload = byteArrayOf(69,120,105,102,0,0,73,73,42,0,8,0,0,0,
            1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0)
        val length = payload.size + 2
        val header = byteArrayOf(0xff.toByte(),0xe1.toByte(),(length shr 8).toByte(),length.toByte())
        val oriented = AvatarRasterPipeline.decode(jpeg.copyOfRange(0, 2) + header + payload + jpeg.copyOfRange(2, jpeg.size))
        assertEquals(100, oriented.width); assertEquals(200, oriented.height)
        val bytes = AvatarRasterPipeline.variants(oriented, AvatarCrop()).getValue(512)
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("Exif"))
        val corrupt = payload.copyOf().also { it[10] = 0x7f; it[11] = 0x7f }
        val fallback = AvatarRasterPipeline.decode(jpeg.copyOfRange(0, 2) + header + corrupt + jpeg.copyOfRange(2, jpeg.size))
        assertEquals(200, fallback.width)
    }

    @Test fun clipboardRasterIsValidatedAndPreviewKeepsItsAspectRatio() {
        val image = BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB)
        val source = AvatarRasterPipeline.fromClipboardImage(image)
        val preview = ImageIO.read(ByteArrayInputStream(source.previewPng))
        assertEquals(1024, preview.width); assertEquals(512, preview.height)
        assertEquals(AvatarImageFailure.TOO_LARGE, assertFailsWith<AvatarImageException> {
            AvatarRasterPipeline.fromClipboardImage(object : java.awt.Image() {
                override fun getWidth(observer: java.awt.image.ImageObserver?) = 8193
                override fun getHeight(observer: java.awt.image.ImageObserver?) = 1
                override fun getSource(): java.awt.image.ImageProducer = error("must not decode")
                override fun getGraphics(): java.awt.Graphics = error("must not allocate")
                override fun getProperty(name: String?, observer: java.awt.image.ImageObserver?): Any = error("unused")
            }) }.reason)
    }
}
