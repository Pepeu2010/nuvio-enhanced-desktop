package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.BoundedFileCache
import com.nuvio.app.core.storage.DesktopMediaCache
import com.nuvio.app.core.storage.MediaCacheCategory
import com.nuvio.app.features.player.metadata.SceneBookmarkScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

internal data class TimelineFrame(val requestedMs: Long, val decodedPositionMs: Long, val pngDataUri: String,
    val filmstrip: List<TimelineFrame> = emptyList())

/** One conflated, device-local decoder per player; all expensive work stays off the EDT. */
internal class DesktopTimelineFrames(
    private val deliver: (SceneBookmarkScope, TimelineFrame?, () -> Boolean) -> Unit,
    private val create: (String, Array<String>) -> Long = NativePlayerBridge::createTimelineWorker,
    private val capture: (Long, Long) -> ByteArray? = NativePlayerBridge::captureTimelineFrame,
    private val cancel: (Long) -> Unit = NativePlayerBridge::cancelTimelineWorker,
    private val dispose: (Long) -> Unit = NativePlayerBridge::disposeTimelineWorker,
    private val cache: () -> BoundedFileCache = { DesktopMediaCache.files(MediaCacheCategory.THUMBNAILS) },
    private val filmstripCache: () -> BoundedFileCache = { DesktopMediaCache.files(MediaCacheCategory.FILMSTRIP) },
) : AutoCloseable {
    private data class Session(val scope: SceneBookmarkScope, val source: String, val headers: List<String>, val cacheIdentity: String)
    private data class Request(val session: Session, val position: Long, val duration: Long, val revision: Long, val filmstrip: Boolean)
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "telumia-timeline-decoder").apply { isDaemon = true } }
    private val session = AtomicReference<Session?>()
    private val desired = AtomicReference<Request?>()
    private val revision = AtomicLong()
    private val scheduled = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val handle = AtomicLong()
    // These two fields belong exclusively to executor.
    private var nativeSession: Session? = null
    private var failedSession: Session? = null

    fun configure(scope: SceneBookmarkScope?, source: String, headers: List<String>) {
        if (closed.get()) return
        val next = scope?.takeIf { source.isNotBlank() && source.length <= 16384 && source.none { it == '\u0000' || it == '\r' || it == '\n' } &&
            headers.size <= 32 && headers.all { it.length <= 4096 && it.none { c -> c == '\u0000' || c == '\r' || c == '\n' } } }?.let {
            val identity = Json.encodeToString(listOf(it.owner.accountId, it.owner.profileIndex.toString(), it.owner.profileId,
                it.mediaType, it.mediaId, it.videoId, it.editionKey, digest(source), digest(Json.encodeToString(headers))))
            Session(it, source, headers.toList(), identity)
        }
        if (session.get() == next) return
        session.set(next); desired.set(null); revision.incrementAndGet()
        handle.get().takeIf { it != 0L }?.let { runCatching { cancel(it) } }
        schedule()
    }

    fun request(positionMs: Long, durationMs: Long, filmstrip: Boolean = false) {
        val owner = session.get() ?: return
        if (closed.get() || durationMs !in 1..604800000 || positionMs !in 0 until durationMs) return
        val bucket = positionMs / 250 * 250
        val current = desired.get()
        if (current?.session === owner && current.position == bucket && current.filmstrip == filmstrip) return
        val next = Request(owner, bucket, durationMs, revision.incrementAndGet(), filmstrip)
        desired.set(next)
        // Finish the current seek before consuming the newest position. Repeated pointer events
        // must not keep cancelling the initial HTTP load; owner changes and leaving still cancel.
        schedule()
    }

    fun clear() {
        desired.set(null); revision.incrementAndGet()
        handle.get().takeIf { it != 0L }?.let { runCatching { cancel(it) } }
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                do {
                    if (closed.get() || nativeSession !== session.get()) releaseDecoder()
                    if (closed.get()) break
                    val request = desired.getAndSet(null) ?: break
                    if (!owns(request)) continue
                    val frame = frameFor(request, request.position, cache, false)
                    if (owns(request)) {
                        deliver(request.session.scope, frame) { owns(request) }
                    }
                    if (frame != null && request.filmstrip && owns(request)) {
                        val neighbors = mutableListOf(frame.copy(filmstrip = emptyList()))
                        for (position in neighborPositions(request.position, request.duration)) {
                            if (!owns(request)) break
                            if (position == request.position) continue
                            frameFor(request, position, filmstripCache, true)?.let { neighbors += it }
                        }
                        if (owns(request)) deliver(request.session.scope,
                            frame.copy(filmstrip = neighbors.distinctBy { it.decodedPositionMs }.sortedBy { it.decodedPositionMs }.take(5))) { owns(request) }
                    }
                } while (true)
            } finally {
                scheduled.set(false)
                if (closed.get()) { releaseDecoder(); executor.shutdown() }
                else if (desired.get() != null || (nativeSession != null && nativeSession !== session.get())) schedule()
            }
        }
    }

    private fun owns(request: Request) = !closed.get() && session.get() === request.session && revision.get() == request.revision
    private fun frameFor(request: Request, position: Long, storage: () -> BoundedFileCache, small: Boolean): TimelineFrame? {
        val key = "timeline-raw-v1:${request.session.cacheIdentity}:${request.duration}:$position"
        val stored = runCatching { storage().get(key) }.getOrNull()
        stored?.let { decode(it, position, request.duration, small) }?.let { return it }
        if (!owns(request) || failedSession === request.session) return null
        if (handle.get() == 0L) {
            val created = runCatching { create(request.session.source, request.session.headers.toTypedArray()) }.getOrDefault(0L)
            handle.set(created); nativeSession = request.session
            if (created == 0L) failedSession = request.session
        }
        val raw = if (owns(request) && handle.get() != 0L) runCatching { capture(handle.get(), position) }.getOrNull() else null
        val frame = raw?.let { decode(it, position, request.duration, small) }
        if (frame != null && raw != null && owns(request)) runCatching { storage().put(key, raw) }
        return frame
    }
    private fun releaseDecoder() {
        handle.getAndSet(0).takeIf { it != 0L }?.let { runCatching { dispose(it) } }
        nativeSession = null; failedSession = null
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session.set(null); clear(); schedule()
    }

    companion object {
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        internal fun neighborPositions(position: Long, duration: Long): List<Long> =
            (-2..2).map { position + it * 1500L }.filter { it in 0 until duration }.distinct()
        /** Strict allocation bounds apply to native bytes and persisted cache alike. */
        internal fun decode(bytes: ByteArray, requestedMs: Long, durationMs: Long, small: Boolean = false): TimelineFrame? = runCatching {
            if (bytes.size !in 24..230420 || !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(84, 70, 82, 49))) return null
            val wire = ByteBuffer.wrap(bytes); wire.position(4)
            val width = wire.int; val height = wire.int; val actual = wire.long
            if (width !in 1..320 || height !in 1..180 || bytes.size != 20 + width * height * 4 ||
                durationMs !in 1..604800000 || requestedMs !in 0 until durationMs || actual !in 0 until durationMs ||
                kotlin.math.abs(actual - requestedMs) > 1250) return null
            val divisor = if (small && (width > 160 || height > 90)) 2 else 1
            val image = BufferedImage(maxOf(1, width / divisor), maxOf(1, height / divisor), BufferedImage.TYPE_INT_RGB)
            for (y in 0 until height) for (x in 0 until width) {
                val blue = wire.get().toInt() and 255; val green = wire.get().toInt() and 255; val red = wire.get().toInt() and 255; wire.get()
                if (x % divisor == 0 && y % divisor == 0 && x / divisor < image.width && y / divisor < image.height)
                    image.setRGB(x / divisor, y / divisor, (red shl 16) or (green shl 8) or blue)
            }
            val output = ByteArrayOutputStream()
            if (!ImageIO.write(image, "png", output)) return null
            TimelineFrame(requestedMs, actual, "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()))
        }.getOrNull()
    }
}
