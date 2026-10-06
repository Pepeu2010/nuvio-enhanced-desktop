package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedFileCacheTest {
    @Test fun evictionUsesByteQuotaAndRecencyWithoutTouchingDurableOrUnrelatedFiles() {
        val parent = Files.createTempDirectory("telumia-cache-test")
        val cacheDirectory = parent.resolve("cache")
        Files.createDirectories(cacheDirectory)
        val durable = parent.resolve("download.mp4").also { Files.write(it, byteArrayOf(9)) }
        val unrelated = cacheDirectory.resolve("settings.json").also { Files.write(it, byteArrayOf(8)) }
        var now = 0L
        val cache = BoundedFileCache(cacheDirectory, { 8L }, clock = { ++now })
        assertTrue(cache.put("first", byteArrayOf(1,1,1,1)))
        assertTrue(cache.put("second", byteArrayOf(2,2,2,2)))
        assertContentEquals(byteArrayOf(1,1,1,1), cache.get("first"))
        assertTrue(cache.put("third", byteArrayOf(3,3,3,3)))
        assertTrue(cache.get("second") == null)
        assertContentEquals(byteArrayOf(1,1,1,1), cache.get("first"))
        assertContentEquals(byteArrayOf(9), Files.readAllBytes(durable))
        assertContentEquals(byteArrayOf(8), Files.readAllBytes(unrelated))
    }

    @Test fun disabledWritesDoNotDiscardPreviouslyCachedOfflineData() {
        val directory = Files.createTempDirectory("telumia-cache-offline")
        var quota = 16L
        val cache = BoundedFileCache(directory, { quota })
        assertTrue(cache.put("metadata", byteArrayOf(1,2,3)))
        quota = 0
        assertFalse(cache.put("new", byteArrayOf(4)))
        assertContentEquals(byteArrayOf(1,2,3), cache.get("metadata"))
    }

    @Test fun hostileKeysStayHashedAndOversizedWritesPreserveTheExistingEntry() {
        val directory = Files.createTempDirectory("telumia-cache-keys")
        val cache = BoundedFileCache(directory, {4L}, maxFiles = 2)
        val key = "../../outside/token?secret=never-in-filename"
        assertTrue(cache.put(key, byteArrayOf(1)))
        assertFalse(cache.put(key, byteArrayOf(1,2,3,4,5)))
        assertContentEquals(byteArrayOf(1), cache.get(key))
        assertTrue(Files.list(directory).use { it.allMatch { path -> path.fileName.toString().matches(Regex("[a-f0-9]{64}\\.cache")) } })
    }

    @Test fun countAndStorageGuardsPreventUnboundedCaches() {
        val directory = Files.createTempDirectory("telumia-cache-count")
        var freeSpace = true
        var now = 0L
        val cache = BoundedFileCache(directory, {100L}, maxFiles = 2, canWrite = { freeSpace }, clock = { ++now })
        repeat(5) { assertTrue(cache.put("$it", byteArrayOf(it.toByte()))) }
        assertTrue(Files.list(directory).use { it.count() } == 2L)
        freeSpace = false
        assertFalse(cache.put("full", byteArrayOf(8)))
        assertTrue(cache.get("4") != null)
    }
}
