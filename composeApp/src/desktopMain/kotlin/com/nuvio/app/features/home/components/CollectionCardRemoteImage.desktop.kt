package com.nuvio.app.features.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.jvm.javaio.toInputStream
import com.nuvio.app.core.ui.DesktopGifLimits
import com.nuvio.app.core.ui.readGifBytesBounded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

private const val MAX_FRAME_DIMENSION = 512

private val desktopGifHttpClient by lazy {
    HttpClient(CIO) {
        followRedirects = true
        engine {
            requestTimeout = 15_000
        }
    }
}

private val downloadSemaphore = Semaphore(4)

// Shares the IMAGES quota with the existing Coil loader; cached reads remain usable offline.
private fun readGifDiskCache(url: String): ByteArray? = com.nuvio.app.core.storage.DesktopMediaCache.readGif(url)

private fun writeGifDiskCache(url: String, bytes: ByteArray) {
    com.nuvio.app.core.storage.DesktopMediaCache.gifCache.put(url, bytes)
}

internal suspend fun downloadDesktopGifBytes(url: String, client: HttpClient = desktopGifHttpClient,
    limit: Int = DesktopGifLimits.MAX_BYTES): ByteArray = client.prepareGet(url) {
    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
}.execute { response ->
    require(response.status.value in 200..299) { "GIF response failed" }
    response.contentLength()?.let { require(it in 0..limit.toLong()) { "GIF response exceeds the byte limit" } }
    response.bodyAsChannel().toInputStream().use { readGifBytesBounded(it,limit) }
}

internal class GifCodecHolder(
    val codec: Codec,
    val frameDelaysMs: List<Long>,
    val width: Int,
    val height: Int,
    val targetWidth: Int,
    val targetHeight: Int,
    val needsScale: Boolean,
) {
    private var users = 0
    private var retired = false
    private var closed = false
    @Synchronized fun acquire(): Boolean {
        if (closed) return false
        users++
        return true
    }
    @Synchronized fun release() {
        check(users > 0)
        users--
        closeIfUnused()
    }
    @Synchronized fun retire() {
        retired = true
        closeIfUnused()
    }
    private fun closeIfUnused() {
        if (retired && users == 0 && !closed) { closed = true; codec.close() }
    }
}

// At most 15 cached codecs; each encoded source is capped at 8 MiB. Active cards
// keep a lease so eviction cannot free a codec while a frame is being read.
private val gifCodecCache = object : LinkedHashMap<String, GifCodecHolder?>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GifCodecHolder?>?): Boolean {
        val shouldRemove = size > 15
        if (shouldRemove) {
            try {
                eldest?.value?.retire()
            } catch (_: Exception) {}
        }
        return shouldRemove
    }
}

// Long-lived scope for background prefetch/decode work that should survive a single card's
// composition (e.g. the card scrolls off-screen right after a prefetch was kicked off).
private val gifLoaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// Tracks in-flight loads so two composables hovering/prefetching the same url concurrently
// share one download+decode instead of racing two network requests.
private val inFlightGifLoads = mutableMapOf<String, Deferred<GifCodecHolder?>>()

private suspend fun loadDesktopGifCodec(url: String): GifCodecHolder? {
    synchronized(gifCodecCache) {
        if (gifCodecCache.containsKey(url)) {
            return gifCodecCache[url]
        }
    }

    val deferred = synchronized(inFlightGifLoads) {
        inFlightGifLoads.getOrPut(url) {
            gifLoaderScope.async { downloadSemaphore.withPermit { decodeGifCodec(url) } }
        }
    }

    val holder = try {
        deferred.await()
    } finally {
        synchronized(inFlightGifLoads) {
            if (inFlightGifLoads[url] === deferred) inFlightGifLoads.remove(url)
        }
    }

    synchronized(gifCodecCache) {
        gifCodecCache[url] = holder
    }
    return holder
}

private suspend fun decodeGifCodec(url: String): GifCodecHolder? {
    var owned: Codec? = null
    return try {
        // Disk cache first - avoids a network round-trip on every fresh app launch.
        val cached = readGifDiskCache(url)
        val bytes = cached ?: downloadDesktopGifBytes(url)

        DesktopGifLimits.validateHeader(bytes)
        val codec = Data.makeFromBytes(bytes).use { Codec.makeFromData(it) }.also { owned = it }
        val count = codec.frameCount
        if (count !in 2..DesktopGifLimits.MAX_FRAMES) return null
        val w = codec.width
        val h = codec.height
        if (!DesktopGifLimits.safeDimensions(w,h)) return null

        val scale = if (w > MAX_FRAME_DIMENSION || h > MAX_FRAME_DIMENSION) {
            minOf(MAX_FRAME_DIMENSION.toFloat() / w, MAX_FRAME_DIMENSION.toFloat() / h)
        } else 1f

        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val needsScale = tw != w || th != h

        val delays = List(count) { i ->
            val duration = codec.getFrameInfo(i).duration
            if (duration > 0) duration.toLong() else 100L
        }
        if (cached == null) writeGifDiskCache(url, bytes)
        GifCodecHolder(codec, delays, w, h, tw, th, needsScale).also { owned = null }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    } finally {
        owned?.close()
    }
}

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    staticImageUrl: String?,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()

    val shouldAnimate = animateIfPossible && (isHovered || staticImageUrl.isNullOrBlank())

    var composeBitmap by remember(imageUrl) { mutableStateOf<ImageBitmap?>(null) }

    // Prefetch as soon as the card becomes visible (not on hover) so the codec is already
    // downloaded/decoded and cached by the time the user actually hovers - hover then reads
    // straight from gifCodecCache with zero network/decode delay.
    if (animateIfPossible) {
        LaunchedEffect(imageUrl) {
            if (synchronized(gifCodecCache) { !gifCodecCache.containsKey(imageUrl) }) {
                loadDesktopGifCodec(imageUrl)
            }
        }
    }

    if (shouldAnimate) {
        var codecHolder by remember(imageUrl) {
            mutableStateOf(synchronized(gifCodecCache) { gifCodecCache[imageUrl] })
        }

        LaunchedEffect(imageUrl) {
            if (codecHolder == null && synchronized(gifCodecCache) { !gifCodecCache.containsKey(imageUrl) }) {
                codecHolder = loadDesktopGifCodec(imageUrl)
            }
        }

        val currentHolder = codecHolder
        if (currentHolder != null && currentHolder.frameDelaysMs.isNotEmpty()) {
            var animationLease by remember(currentHolder) { mutableStateOf(false) }
            DisposableEffect(currentHolder) {
                animationLease = currentHolder.acquire()
                onDispose { if (animationLease) currentHolder.release() }
            }
            var frameIndex by remember(imageUrl) { mutableStateOf(0) }

            // Allocate ONLY ONE single reusable Skia Bitmap for this card while hovered
            val singleBitmap = remember(imageUrl, currentHolder) {
                try {
                    Bitmap().apply {
                        allocPixels(ImageInfo.makeN32Premul(currentHolder.targetWidth, currentHolder.targetHeight))
                    }
                } catch (_: Exception) {
                    null
                }
            }

            // Full-res buffer if downscaling is required
            val fullBitmap = remember(imageUrl, currentHolder) {
                if (currentHolder.needsScale) {
                    try {
                        Bitmap().apply {
                            allocPixels(ImageInfo.makeN32Premul(currentHolder.width, currentHolder.height))
                        }
                    } catch (_: Exception) {
                        null
                    }
                } else null
            }

            DisposableEffect(singleBitmap, fullBitmap) {
                onDispose {
                    try {
                        singleBitmap?.close()
                    } catch (_: Exception) {}
                    try {
                        fullBitmap?.close()
                    } catch (_: Exception) {}
                }
            }

            if (singleBitmap != null) {
                LaunchedEffect(imageUrl, currentHolder, singleBitmap, fullBitmap, animationLease) {
                    if (!animationLease) return@LaunchedEffect
                    while (true) {
                        try {
                            if (currentHolder.needsScale && fullBitmap != null) {
                                currentHolder.codec.readPixels(fullBitmap, frameIndex)
                                val skiaImg = Image.makeFromBitmap(fullBitmap)
                                try {
                                    Canvas(singleBitmap).use { canvas -> canvas.drawImageRect(
                                        skiaImg,
                                        Rect.makeWH(currentHolder.width.toFloat(), currentHolder.height.toFloat()),
                                        Rect.makeWH(currentHolder.targetWidth.toFloat(), currentHolder.targetHeight.toFloat()),
                                        SamplingMode.LINEAR,
                                        null,
                                        true,
                                    ) }
                                } finally {
                                    skiaImg.close()
                                }
                            } else {
                                currentHolder.codec.readPixels(singleBitmap, frameIndex)
                            }
                            composeBitmap = singleBitmap.asComposeImageBitmap()
                        } catch (_: Exception) {}

                        val delayMs = currentHolder.frameDelaysMs.getOrElse(frameIndex) { 100L }
                        delay(delayMs)
                        frameIndex = (frameIndex + 1) % currentHolder.frameDelaysMs.size
                    }
                }
            }
        }
    } else {
        composeBitmap = null
    }

    val context = LocalPlatformContext.current
    val displayImageUrl = if (animateIfPossible) {
        staticImageUrl?.takeIf { it.isNotBlank() } ?: imageUrl
    } else {
        imageUrl
    }
    val request = remember(context, displayImageUrl) {
        ImageRequest.Builder(context)
            .data(displayImageUrl)
            .memoryCacheKey("home-collection:$displayImageUrl")
            .diskCacheKey(displayImageUrl)
            .build()
    }

    Box(modifier = modifier.hoverable(hoverInteractionSource)) {
        // Stacked Base Layer: uses NuvioAsyncImage with desktop high-quality anti-aliased scaling
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            contentScale = contentScale,
            filterQuality = FilterQuality.High,
        )

        val animatedFrame = composeBitmap
        if (animatedFrame != null) {
            Image(
                bitmap = animatedFrame,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
                filterQuality = FilterQuality.High,
            )
        }
    }
}
