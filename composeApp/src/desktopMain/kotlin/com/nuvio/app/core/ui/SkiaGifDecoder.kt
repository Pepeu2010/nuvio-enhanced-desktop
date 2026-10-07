package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import okio.use

private const val MAX_FRAME_DIMENSION = 512

internal object DesktopGifLimits {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_FRAMES = 512
    fun safeDimensions(width: Int, height: Int) = width in 1..8192 && height in 1..8192 &&
        width.toLong() * height <= 4L * 1024 * 1024
    fun validateHeader(bytes: ByteArray) {
        require(bytes.size in 10..MAX_BYTES)
        require(bytes.copyOfRange(0,6).toString(Charsets.US_ASCII) in setOf("GIF87a","GIF89a"))
        fun word(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset+1].toInt() and 255) shl 8)
        require(safeDimensions(word(6),word(8))) { "GIF dimensions exceed the working memory limit" }
    }
}

/** Stops after one overflow byte even for unknown-length, chunked responses. */
internal fun readGifBytesBounded(input: java.io.InputStream, limit: Int = DesktopGifLimits.MAX_BYTES): ByteArray {
    require(limit in 1..DesktopGifLimits.MAX_BYTES)
    val output = java.io.ByteArrayOutputStream(minOf(limit,4096))
    val buffer = ByteArray(16384)
    while(true) {
        val count=input.read(buffer,0,minOf(buffer.size,limit-output.size()+1))
        if(count<0)break
        if(count==0) {
            val next=input.read()
            if(next<0)break
            require(output.size()<limit) { "GIF response exceeds the byte limit" }
            output.write(next)
        } else {
            require(output.size()+count<=limit) { "GIF response exceeds the byte limit" }
            output.write(buffer,0,count)
        }
    }
    return output.toByteArray()
}

class SkiaGifDecoder(
    private val source: ImageSource,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bytes = withContext(Dispatchers.IO) {
            source.source().use { okioSource ->
                readGifBytesBounded(okioSource.inputStream())
            }
        }
        DesktopGifLimits.validateHeader(bytes)

        val codec = Data.makeFromBytes(bytes).use { Codec.makeFromData(it) }
        try {
        val count = codec.frameCount
        if (count !in 1..DesktopGifLimits.MAX_FRAMES) return null

        val w = codec.width
        val h = codec.height
        if (!DesktopGifLimits.safeDimensions(w,h)) return null

        val scale = if (w > MAX_FRAME_DIMENSION || h > MAX_FRAME_DIMENSION) {
            minOf(MAX_FRAME_DIMENSION.toFloat() / w, MAX_FRAME_DIMENSION.toFloat() / h)
        } else 1f

        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val needsScale = tw != w || th != h

        val bitmap: Bitmap
        if (needsScale) {
            val fullBitmap = Bitmap().apply {
                allocPixels(ImageInfo.makeN32Premul(w, h))
            }
            try {
                codec.readPixels(fullBitmap, 0)
                bitmap = Bitmap().apply {
                    allocPixels(ImageInfo.makeN32Premul(tw, th))
                }
                val skiaImg = Image.makeFromBitmap(fullBitmap)
                try {
                    Canvas(bitmap).use { canvas -> canvas.drawImageRect(
                        skiaImg,
                        Rect.makeWH(w.toFloat(), h.toFloat()),
                        Rect.makeWH(tw.toFloat(), th.toFloat()),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    ) }
                } finally {
                    skiaImg.close()
                }
            } finally {
                fullBitmap.close()
            }
        } else {
            bitmap = Bitmap().apply {
                allocPixels(ImageInfo.makeN32Premul(tw, th))
            }
            codec.readPixels(bitmap, 0)
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = needsScale,
        )
        } finally { codec.close() }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val mimeType = result.mimeType
            val isGif = mimeType?.equals("image/gif", ignoreCase = true) == true ||
                result.source.fileOrNull()?.name?.endsWith(".gif", ignoreCase = true) == true
            if (!isGif) return null
            return SkiaGifDecoder(result.source)
        }
    }
}
