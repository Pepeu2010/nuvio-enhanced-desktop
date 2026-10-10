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

internal data class TimelineFrame(val requestedMs: Long, val decodedPositionMs: Long, val pngDataUri: String)

/** One conflated, device-local decoder per player; all expensive work stays off the EDT. */
internal class DesktopTimelineFrames(
    private val deliver: (SceneBookmarkScope, TimelineFrame?, () -> Boolean) -> Unit,
    private val create: (String, Array<String>) -> Long = NativePlayerBridge::createTimelineWorker,
    private val capture: (Long, Long) -> ByteArray? = NativePlayerBridge::captureTimelineFrame,
    private val cancel: (Long) -> Unit = NativePlayerBridge::cancelTimelineWorker,
    private val dispose: (Long) -> Unit = NativePlayerBridge::disposeTimelineWorker,
    private val cache: () -> BoundedFileCache = { DesktopMediaCache.files(MediaCacheCategory.THUMBNAILS) },
) : AutoCloseable {
    private data class Session(val scope: SceneBookmarkScope, val source: String, val headers: List<String>, val cacheIdentity: String)
    private data class Request(val session: Session, val position: Long, val duration: Long, val revision: Long)
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
                it.mediaType, it.mediaId, it.videoId, it.editionKey, digest(Json.encodeToString(headers))))
            Session(it, source, headers.toList(), identity)
        }
        if (session.get() == next) return
        session.set(next); desired.set(null); revision.incrementAndGet()
        handle.get().takeIf { it != 0L }?.let { runCatching { cancel(it) } }
        schedule()
    }

    fun request(positionMs: Long, durationMs: Long) {
        val owner = session.get() ?: return
        if (closed.get() || durationMs !in 1..604800000 || positionMs !in 0 until durationMs) return
        val bucket = positionMs / 250 * 250
        val current = desired.get()
        if (current?.session === owner && current.position == bucket) return
        val next = Request(owner, bucket, durationMs, revision.incrementAndGet())
        desired.set(next)
        handle.get().takeIf { it != 0L }?.let { runCatching { cancel(it) } }
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
                    val key = "timeline-raw-v1:${request.session.cacheIdentity}:${request.duration}:${request.position}"
                    val stored = runCatching { cache().get(key) }.getOrNull()
                    var raw = stored?.takeIf { decode(it, request.position, request.duration) != null }
                    if (raw == null && failedSession !== request.session) {
                        if (handle.get() == 0L) {
                            val created = runCatching { create(request.session.source, request.session.headers.toTypedArray()) }.getOrDefault(0L)
                            handle.set(created); nativeSession = request.session
                            if (created == 0L) failedSession = request.session
                        }
                        if (owns(request) && handle.get() != 0L) raw = runCatching { capture(handle.get(), request.position) }.getOrNull()
                    }
                    val frame = raw?.let { decode(it, request.position, request.duration) }
                    if (owns(request)) {
                        if (frame != null && stored == null) runCatching { cache().put(key, raw!!) }
                        deliver(request.session.scope, frame) { owns(request) }
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
        /** Strict allocation bounds apply to native bytes and persisted cache alike. */
        internal fun decode(bytes: ByteArray, requestedMs: Long, durationMs: Long): TimelineFrame? = runCatching {
            if (bytes.size !in 24..230420 || !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(84, 70, 82, 49))) return null
            val wire = ByteBuffer.wrap(bytes); wire.position(4)
            val width = wire.int; val height = wire.int; val actual = wire.long
            if (width !in 1..320 || height !in 1..180 || bytes.size != 20 + width * height * 4 ||
                durationMs !in 1..604800000 || requestedMs !in 0 until durationMs || actual !in 0 until durationMs ||
                kotlin.math.abs(actual - requestedMs) > 1250) return null
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until height) for (x in 0 until width) {
                val blue = wire.get().toInt() and 255; val green = wire.get().toInt() and 255; val red = wire.get().toInt() and 255; wire.get()
                image.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
            }
            val output = ByteArrayOutputStream()
            if (!ImageIO.write(image, "png", output)) return null
            TimelineFrame(requestedMs, actual, "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()))
        }.getOrNull()
    }
}
