package com.nuvio.app.features.player.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.nuvio.app.core.storage.BoundedFileCache
import com.nuvio.app.features.player.metadata.SceneBookmarkScope
import com.nuvio.app.features.profiles.ProfileAvatarScope
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses the packaged JNI bridge and actual bundled libmpv, not a simulated seek callback. */
class NativeTimelineFramesTest {
    @Test fun authenticatedHttpFixturePreservesCommaAndBackslashHeaderValues() {
        val bytes=originalAvi();val requests=AtomicInteger();val header="alpha,beta\\tail"
        val observedHeaders=java.util.concurrent.CopyOnWriteArrayList<String>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/original.avi") { exchange ->
            try {
                observedHeaders+=exchange.requestHeaders.getFirst("X-Telumia-Fixture")?:"<missing>"
                if(exchange.requestHeaders.getFirst("X-Telumia-Fixture")!=header) {exchange.sendResponseHeaders(403,-1);return@createContext}
                requests.incrementAndGet()
                val range=Regex("bytes=(\\d+)-(\\d*)").matchEntire(exchange.requestHeaders.getFirst("Range").orEmpty())
                val start=range?.groupValues?.get(1)?.toIntOrNull()?:0
                val end=range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex)?:bytes.lastIndex
                if(start !in bytes.indices||end<start) {exchange.responseHeaders.add("Content-Range","bytes */${bytes.size}");exchange.sendResponseHeaders(416,-1);return@createContext}
                exchange.responseHeaders.add("Content-Type","video/x-msvideo")
                exchange.responseHeaders.add("Accept-Ranges","bytes")
                if(range!=null) exchange.responseHeaders.add("Content-Range","bytes $start-$end/${bytes.size}")
                if(exchange.requestMethod=="HEAD") exchange.sendResponseHeaders(200,-1)
                else { exchange.sendResponseHeaders(if(range==null)200 else 206,(end-start+1).toLong());exchange.responseBody.write(bytes,start,end-start+1) }
            } finally { exchange.close() }
        }
        server.start()
        val handle=NativePlayerBridge.createTimelineWorker("http://127.0.0.1:${server.address.port}/original.avi",arrayOf("X-Telumia-Fixture: $header"))
        try {
            assertTrue(handle!=0L)
            for(position in listOf(500L,4500L)) {
                val frame=assertNotNull(DesktopTimelineFrames.decode(assertNotNull(NativePlayerBridge.captureTimelineFrame(handle,position),
                    "HTTP frame $position; accepted=$requests; observed fixture headers=$observedHeaders"),position,6000))
                assertEquals(position,frame.decodedPositionMs)
            }
            assertTrue(requests.get()>0,"Actual HTTP requests must pass the fixture header gate")
        } finally { NativePlayerBridge.disposeTimelineWorker(handle);server.stop(0) }
    }

    @Test fun cancellationReturnsBeforeDelayedHttpBodyAndDisposedHandleCannotCapture() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/delayed.avi") { exchange ->
            try {
                val bytes=originalAvi();exchange.sendResponseHeaders(200,bytes.size.toLong());entered.countDown()
                release.await(5,TimeUnit.SECONDS)
                runCatching {exchange.responseBody.write(bytes)}
            } finally {exchange.close()}
        }
        server.start();val executor=Executors.newSingleThreadExecutor()
        val handle=NativePlayerBridge.createTimelineWorker("http://127.0.0.1:${server.address.port}/delayed.avi",emptyArray())
        try {
            assertTrue(handle!=0L)
            val pending=executor.submit<ByteArray?> { NativePlayerBridge.captureTimelineFrame(handle,500) }
            assertTrue(entered.await(4,TimeUnit.SECONDS))
            NativePlayerBridge.cancelTimelineWorker(handle)
            assertNull(pending.get(1500,TimeUnit.MILLISECONDS),"Cancellation must return without waiting for the blocked response body")
        } finally {
            release.countDown();NativePlayerBridge.disposeTimelineWorker(handle);server.stop(0);executor.shutdownNow()
        }
        assertNull(NativePlayerBridge.captureTimelineFrame(handle,500))
    }

    @Test fun actualWorkerFilmstripContainsDistinctAvailableNeighborFrames() {
        val directory=Files.createTempDirectory("telumia-native-filmstrip")
        val fixture=directory.resolve("original.avi");Files.write(fixture,originalAvi())
        val cache=BoundedFileCache(directory.resolve("thumbnails"),{1048576L})
        val strip=BoundedFileCache(directory.resolve("filmstrip"),{1048576L})
        val completed=CountDownLatch(1);val released=CountDownLatch(1)
        var result: TimelineFrame?=null
        val owner=SceneBookmarkScope(ProfileAvatarScope("fixture",1,"profile"),"movie","movie","movie",SceneBookmarkScope.sourceEdition(fixture.toString()))
        val worker=DesktopTimelineFrames(deliver={_,frame,current-> if(current()&&frame?.filmstrip?.size==4) {result=frame;completed.countDown()}},
            dispose={NativePlayerBridge.disposeTimelineWorker(it);released.countDown()},cache={cache},filmstripCache={strip})
        try {
            worker.configure(owner,fixture.toString(),emptyList());worker.request(3000,6000,filmstrip=true)
            assertTrue(completed.await(8,TimeUnit.SECONDS))
            val frames=assertNotNull(result).filmstrip
            assertEquals(listOf(0L,1500L,3000L,4500L),frames.map {it.decodedPositionMs})
            val colors=frames.map {frame ->
                val image=ImageIO.read(Base64.getDecoder().decode(frame.pngDataUri.substringAfter(',')).inputStream())
                assertTrue(image.width in 1..320&&image.height in 1..180)
                image.getRGB(image.width/2,image.height/2) and 0xffffff
            }
            assertEquals(listOf(0xff0202,0xff0505,0x08ff08,0x0b0bff),colors)
        } finally { worker.close();assertTrue(released.await(5,TimeUnit.SECONDS));Files.delete(fixture) }
    }

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
