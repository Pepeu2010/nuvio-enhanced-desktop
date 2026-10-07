package com.nuvio.app.features.profiles

import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.roundToInt

internal enum class AvatarImageFailure { EMPTY, UNSUPPORTED, TOO_LARGE, INVALID }
internal class AvatarImageException(val reason: AvatarImageFailure) : Exception(reason.name)
internal data class AvatarCrop(val centerX: Float = .5f, val centerY: Float = .5f, val zoom: Float = 1f) {
    fun validated(): AvatarCrop {
        if (!centerX.isFinite() || !centerY.isFinite() || !zoom.isFinite()) throw AvatarImageException(AvatarImageFailure.INVALID)
        return copy(centerX = centerX.coerceIn(0f, 1f), centerY = centerY.coerceIn(0f, 1f), zoom = zoom.coerceIn(1f, 8f))
    }
}
internal class AvatarRasterSource internal constructor(internal val image: BufferedImage, val previewPng: ByteArray) {
    val width: Int get() = image.width
    val height: Int get() = image.height
}

/** Raster imports only. Size checks precede decoding; original metadata is never exported. */
internal object AvatarRasterPipeline {
    const val MAX_INPUT_BYTES = 10 * 1024 * 1024
    private const val MAX_PIXELS = 16L * 1024 * 1024
    val variantSizes = listOf(64, 128, 256, 512)

    fun readFile(path: Path): AvatarRasterSource {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw AvatarImageException(AvatarImageFailure.INVALID)
        if (Files.size(path) > MAX_INPUT_BYTES) throw AvatarImageException(AvatarImageFailure.TOO_LARGE)
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_INPUT_BYTES + 1) }
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): AvatarRasterSource {
        if (bytes.isEmpty()) throw AvatarImageException(AvatarImageFailure.EMPTY)
        if (bytes.size > MAX_INPUT_BYTES) throw AvatarImageException(AvatarImageFailure.TOO_LARGE)
        try {
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) throw AvatarImageException(AvatarImageFailure.UNSUPPORTED)
                val reader = readers.next()
                try {
                    if (reader.formatName.lowercase() !in setOf("png", "jpeg", "jpg", "gif", "bmp"))
                        throw AvatarImageException(AvatarImageFailure.UNSUPPORTED)
                    reader.input = input
                    checkDimensions(reader.getWidth(0), reader.getHeight(0))
                    val decoded = reader.read(0) ?: throw AvatarImageException(AvatarImageFailure.INVALID)
                    val image = orient(decoded, jpegOrientation(bytes))
                    return AvatarRasterSource(image, encode(resize(image, 1024)))
                } finally { reader.dispose() }
            }
        } catch (error: AvatarImageException) { throw error }
        catch (_: Exception) { throw AvatarImageException(AvatarImageFailure.INVALID) }
    }

    fun fromClipboardImage(image: java.awt.Image): AvatarRasterSource {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        checkDimensions(width, height)
        val raster = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = raster.createGraphics()
        try { if (!graphics.drawImage(image, 0, 0, null)) throw AvatarImageException(AvatarImageFailure.INVALID) }
        finally { graphics.dispose() }
        return AvatarRasterSource(raster, encode(resize(raster, 1024)))
    }

    fun variants(source: AvatarRasterSource, crop: AvatarCrop): Map<Int, ByteArray> {
        val selection = selection(source, crop)
        return variantSizes.associateWith { size -> encode(resize(selection, size, allowUpscale = true)) }
    }

    fun preview(source: AvatarRasterSource, crop: AvatarCrop): ByteArray = encode(resize(selection(source, crop), 256, allowUpscale = true))

    private fun selection(source: AvatarRasterSource, crop: AvatarCrop): BufferedImage {
        val normalized = crop.validated()
        val image = source.image
        val side = (minOf(image.width, image.height) / normalized.zoom).roundToInt().coerceAtLeast(1)
        val left = (normalized.centerX * image.width - side / 2f).roundToInt().coerceIn(0, image.width - side)
        val top = (normalized.centerY * image.height - side / 2f).roundToInt().coerceIn(0, image.height - side)
        return image.getSubimage(left, top, side, side)
    }

    private fun checkDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) throw AvatarImageException(AvatarImageFailure.INVALID)
        if (width > 8192 || height > 8192 || width.toLong() * height > MAX_PIXELS)
            throw AvatarImageException(AvatarImageFailure.TOO_LARGE)
    }

    private fun resize(image: BufferedImage, maximum: Int, allowUpscale: Boolean = false): BufferedImage {
        val scale = maximum.toDouble() / maxOf(image.width, image.height)
        val factor = if (allowUpscale) scale else scale.coerceAtMost(1.0)
        val result = BufferedImage((image.width * factor).roundToInt().coerceAtLeast(1),
            (image.height * factor).roundToInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(image, 0, 0, result.width, result.height, null)
        } finally { graphics.dispose() }
        return result
    }

    private fun encode(image: BufferedImage): ByteArray = ByteArrayOutputStream().use {
        if (!ImageIO.write(image, "png", it)) throw AvatarImageException(AvatarImageFailure.INVALID)
        it.toByteArray()
    }

    private fun orient(image: BufferedImage, orientation: Int): BufferedImage {
        if (orientation == 1) return image
        val width = image.width.toDouble(); val height = image.height.toDouble()
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, width, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, width, height)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, height)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, height, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, height, width)
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, width)
            else -> return image
        }
        val result = BufferedImage(if (orientation >= 5) image.height else image.width,
            if (orientation >= 5) image.width else image.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try { graphics.drawRenderedImage(image, transform) } finally { graphics.dispose() }
        return result
    }

    /** Bounded EXIF IFD0 parsing. No recursion, external references or metadata-derived filesystem paths. */
    private fun jpegOrientation(bytes: ByteArray): Int {
        fun u8(index: Int) = bytes[index].toInt() and 255
        if (bytes.size < 4 || u8(0) != 255 || u8(1) != 216) return 1
        var position = 2
        while (position + 4 <= bytes.size) {
            if (u8(position) != 255) return 1
            val marker = u8(position + 1)
            if (marker == 218 || marker == 217) return 1
            if (marker == 255) { position++; continue }
            val length = (u8(position + 2) shl 8) or u8(position + 3)
            if (length < 2 || position.toLong() + 2 + length > bytes.size) return 1
            val end = position + 2 + length
            val exif = position + 4
            if (marker == 225 && exif + 14 <= end && bytes.copyOfRange(exif, exif + 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
                val base = exif + 6
                val little = u8(base) == 73 && u8(base + 1) == 73
                if (!little && !(u8(base) == 77 && u8(base + 1) == 77)) return 1
                fun u16(index: Int) = if (little) u8(index) or (u8(index + 1) shl 8) else (u8(index) shl 8) or u8(index + 1)
                fun u32(index: Int): Long = if (little) u16(index).toLong() or (u16(index + 2).toLong() shl 16)
                    else (u16(index).toLong() shl 16) or u16(index + 2).toLong()
                if (u16(base + 2) != 42) return 1
                val offset = u32(base + 4)
                if (offset < 8 || offset > end - base - 2) return 1
                val directory = base + offset.toInt()
                val count = u16(directory)
                if (count > 1024 || directory.toLong() + 2 + count * 12L > end) return 1
                for (index in 0 until count) {
                    val entry = directory + 2 + index * 12
                    if (u16(entry) == 274 && u16(entry + 2) == 3 && u32(entry + 4) == 1L) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
                }
            }
            position = end
        }
        return 1
    }
}
