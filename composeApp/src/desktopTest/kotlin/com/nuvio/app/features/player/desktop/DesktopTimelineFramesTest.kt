package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.BoundedFileCache
import com.nuvio.app.features.player.metadata.SceneBookmarkScope
import com.nuvio.app.features.profiles.ProfileAvatarScope
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTimelineFramesTest {
    private fun scope(owner: String = "fixture") = SceneBookmarkScope(ProfileAvatarScope(owner, 1, "profile"), "movie", "movie", "movie", SceneBookmarkScope.sourceEdition("fixture"))
    private fun raw(position: Long = 500): ByteArray = ByteBuffer.allocate(24).put(byteArrayOf(84,70,82,49)).putInt(1).putInt(1).putLong(position).put(byteArrayOf(3,3,-1,0)).array()

    @Test fun rejectsMalformedOversizedAndUnrelatedFrames() {
        assertNotNull(DesktopTimelineFrames.decode(raw(), 500, 6000))
        for (bytes in listOf(byteArrayOf(), raw().copyOf(23), raw().also { it[0] = 0 }, raw().also { ByteBuffer.wrap(it).putInt(4, Int.MAX_VALUE) }))
            assertNull(DesktopTimelineFrames.decode(bytes, 500, 6000))
        assertNull(DesktopTimelineFrames.decode(raw(5000), 500, 6000))
        assertNull(DesktopTimelineFrames.decode(raw(6000), 6000, 6000))
        assertNull(DesktopTimelineFrames.decode(raw(), 500, 0))
    }

    @Test fun lateOwnerCannotDeliverOrWriteIntoNewOwnerCache() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val delivered = CountDownLatch(1)
        val captures = AtomicInteger(); val oldOwner = scope("old"); val newOwner = scope("new")
        val cacheDir = Files.createTempDirectory("timeline-owner")
        val cache = BoundedFileCache(cacheDir, { 1024L * 1024 })
        val deliveredOwners = java.util.concurrent.CopyOnWriteArrayList<SceneBookmarkScope>()
        val worker = DesktopTimelineFrames(deliver = { owner, frame, current ->
            if (current() && frame != null) { deliveredOwners += owner; delivered.countDown() }
        }, create = { _, _ -> 1L }, capture = { _, position ->
            if (captures.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            raw(position)
        }, cancel = {}, dispose = {}, cache = { cache })
        try {
            worker.configure(oldOwner, "original-video", emptyList()); worker.request(500, 6000)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.configure(newOwner, "original-video", emptyList()); worker.request(1000, 6000)
            release.countDown(); assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(newOwner), deliveredOwners.toList())
            assertEquals(1L, Files.list(cacheDir).use { it.count() })
        } finally { release.countDown(); worker.close() }
    }

    @Test fun cachedFrameSurvivesDecoderFailureAndSourceChangeCannotReuseIt() {
        val cacheDir = Files.createTempDirectory("timeline-cache")
        val cache = BoundedFileCache(cacheDir, { 1024L * 1024 })
        val first = CountDownLatch(1)
        val captures = AtomicInteger()
        val worker = DesktopTimelineFrames(deliver = { _, frame, current -> if (current() && frame != null) first.countDown() },
            create = { _, _ -> 1L }, capture = { _, position -> captures.incrementAndGet(); raw(position) }, cancel = {}, dispose = {}, cache = { cache })
        worker.configure(scope(), "original-video", emptyList()); worker.request(500, 6000)
        assertTrue(first.await(5, TimeUnit.SECONDS)); worker.close()
        val restored = CountDownLatch(1); val failed = CountDownLatch(1)
        val cachedWorker = DesktopTimelineFrames(deliver = { _, frame, current -> if (current()) { if (frame != null) restored.countDown() else failed.countDown() } },
            create = { _, _ -> 0L }, capture = { _, _ -> error("unsupported decoder") }, cancel = {}, dispose = {}, cache = { cache })
        try {
            cachedWorker.configure(scope(), "original-video", emptyList()); cachedWorker.request(500, 6000)
            assertTrue(restored.await(5, TimeUnit.SECONDS)); assertEquals(1, captures.get())
            cachedWorker.configure(scope().copy(editionKey = SceneBookmarkScope.sourceEdition("different-cut")), "different-cut", emptyList())
            cachedWorker.request(500, 6000); assertTrue(failed.await(5, TimeUnit.SECONDS))
        } finally { cachedWorker.close() }
    }

    @Test fun filmstripUsesOnlyAvailableOrderedNeighborsAndItsOwnBoundedCache() {
        assertEquals(listOf(0L,1500L,3000L), DesktopTimelineFrames.neighborPositions(0,6000))
        assertEquals(listOf(0L,1500L,3000L,4500L), DesktopTimelineFrames.neighborPositions(3000,6000))
        assertEquals(listOf(2500L,4000L,5500L), DesktopTimelineFrames.neighborPositions(5500,6000))
        val thumbnails = BoundedFileCache(Files.createTempDirectory("timeline-center"), { 1048576L })
        val filmDirectory = Files.createTempDirectory("timeline-strip")
        val filmCache = BoundedFileCache(filmDirectory, { 1048576L })
        val delivered=CountDownLatch(1)
        var result: TimelineFrame? = null
        val worker=DesktopTimelineFrames(deliver={_,frame,current-> if(current() && frame?.filmstrip?.isNotEmpty()==true) { result=frame;delivered.countDown() } },
            create={_,_->1L},capture={_,position->if(position==1500L) null else raw(position)},cancel={},dispose={},cache={thumbnails},filmstripCache={filmCache})
        try {
            worker.configure(scope(),"original-video",emptyList());worker.request(3000,6000,filmstrip=true)
            assertTrue(delivered.await(5,TimeUnit.SECONDS))
            val frame=assertNotNull(result)
            assertEquals(3000L,frame.decodedPositionMs)
            assertEquals(listOf(0L,3000L,4500L),frame.filmstrip.map { it.decodedPositionMs })
            assertTrue(frame.filmstrip.all { it.filmstrip.isEmpty() })
            assertEquals(2L,Files.list(filmDirectory).use { it.count() })
        } finally { worker.close() }
    }

    @Test fun continuousPositionChangesConflateWithoutRestartingInitialLoad() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val cancels=AtomicInteger();val calls=java.util.concurrent.CopyOnWriteArrayList<Long>()
        val cache=BoundedFileCache(Files.createTempDirectory("timeline-conflated"),{1048576L})
        val worker=DesktopTimelineFrames(deliver={_,frame,current->if(current()&&frame?.requestedMs==4500L) finished.countDown()},
            create={_,_->1L},capture={_,position-> calls+=position;if(calls.size==1){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))};raw(position)},
            cancel={cancels.incrementAndGet()},dispose={},cache={cache})
        try {
            worker.configure(scope(),"original-video",emptyList());worker.request(500,6000);assertTrue(entered.await(5,TimeUnit.SECONDS))
            for(position in listOf(1000L,2000L,3000L,4500L)) worker.request(position,6000)
            assertEquals(0,cancels.get());release.countDown();assertTrue(finished.await(5,TimeUnit.SECONDS))
            assertEquals(listOf(500L,4500L),calls.toList());worker.clear();assertEquals(1,cancels.get())
        } finally { release.countDown();worker.close() }
    }
}
