package com.nuvio.app.core.ui

import com.nuvio.app.features.home.components.GifCodecHolder
import com.nuvio.app.features.home.components.downloadDesktopGifBytes
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import kotlin.test.*

class DesktopGifSafetyTest {
    @Test fun readsExactlyTheLimitAndOnlyOneOverflowByte() {
        val stream=ByteArrayInputStream(ByteArray(33))
        assertFailsWith<IllegalArgumentException> { readGifBytesBounded(stream,32) }
        assertEquals(0,stream.available())
        val longer=ByteArrayInputStream(ByteArray(4096))
        assertFailsWith<IllegalArgumentException> { readGifBytesBounded(longer,32) }
        assertEquals(4096-33,longer.available())
        assertEquals(32,readGifBytesBounded(ByteArrayInputStream(ByteArray(32)),32).size)
    }

    @Test fun oversizedGifHeaderIsRefusedBeforeNativeBuffersAreAllocated() {
        val bytes=java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==")
        DesktopGifLimits.validateHeader(bytes)
        bytes[6]=0xff.toByte();bytes[7]=0xff.toByte()
        assertFailsWith<IllegalArgumentException> { DesktopGifLimits.validateHeader(bytes) }
        assertFalse(DesktopGifLimits.safeDimensions(8192,8192))
        assertFalse(DesktopGifLimits.safeDimensions(0,512))
    }

    @Test fun activeNativeCodecSurvivesEvictionUntilItsLastCardReleasesIt() {
        val image=java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB)
        for(x in 0..1)for(y in 0..1)image.setRGB(x,y,java.awt.Color.RED.rgb)
        val bytes=java.io.ByteArrayOutputStream().use { output ->
            assertTrue(javax.imageio.ImageIO.write(image,"gif",output))
            output.toByteArray()
        }
        val codec=Data.makeFromBytes(bytes).use { Codec.makeFromData(it) }
        val holder=GifCodecHolder(codec,listOf(100),2,2,2,2,false)
        assertTrue(holder.acquire());assertTrue(holder.acquire())
        holder.retire()
        holder.release()
        Bitmap().use { bitmap ->
            bitmap.allocPixels(ImageInfo.makeN32Premul(2,2))
            codec.readPixels(bitmap,0)
        }
        holder.release()
        assertFalse(holder.acquire())
        holder.retire() // idempotent retirement after the native resource was closed
    }

    @Test fun chunkedHttpResponseIsBoundedWithoutAContentLengthHeader(): Unit = runBlocking(Dispatchers.IO) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/image") { exchange ->
            exchange.sendResponseHeaders(200,0)
            exchange.responseBody.use { it.write(ByteArray(4096)) }
        }
        server.start()
        try {
            HttpClient(CIO).use { client ->
                assertFailsWith<IllegalArgumentException> {
                    downloadDesktopGifBytes("http://127.0.0.1:${server.address.port}/image",client,32)
                }
            }
        } finally { server.stop(0) }
    }

    @Test fun failedHttpResponseNeverReachesTheGifDecoder(): Unit = runBlocking(Dispatchers.IO) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/image") { exchange ->
            exchange.sendResponseHeaders(503,0)
            exchange.responseBody.use { it.write(ByteArray(16)) }
        }
        server.start()
        try {
            HttpClient(CIO).use { client ->
                assertFailsWith<IllegalArgumentException> {
                    downloadDesktopGifBytes("http://127.0.0.1:${server.address.port}/image",client,32)
                }
            }
        } finally { server.stop(0) }
    }
}
