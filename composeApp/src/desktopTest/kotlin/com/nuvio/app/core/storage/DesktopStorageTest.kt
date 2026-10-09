package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import com.sun.nio.file.ExtendedOpenOption
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopStorageTest {
    @Test
    fun unchanged_operations_do_not_rewrite_the_store() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("key", "value")
            val sentinel = FileTime.fromMillis(1_000L)
            Files.setLastModifiedTime(file, sentinel)

            store.putString("key", "value")
            store.remove("missing")
            store.removeAll(listOf("also-missing"))

            assertEquals(sentinel, Files.getLastModifiedTime(file))

            store.putString("key", "updated")

            assertNotEquals(sentinel, Files.getLastModifiedTime(file))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun transientDenialRetriesTheSameClosedPendingFileThenPublishesDurablePreferences() = files { directory ->
        val file = directory.resolve("preferences.properties")
        DesktopStorage.Store(file).putString("key", "old")
        val delays = mutableListOf<Long>()
        var attempts = 0
        val store = DesktopStorage.Store(file) { source, target ->
            publishDesktopPreferenceFile(source, target, move = { pending, destination, _ ->
                attempts++
                if (attempts < 3) throw AccessDeniedException(destination.toString())
                Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING)
            }, pause = { delay ->
                delays += delay
                assertEquals("old", DesktopStorage.Store(file).getString("key"))
            }, retryAccessDenied = true)
        }
        store.putString("key", "new")
        assertEquals(listOf(20L, 40L), delays)
        assertEquals(3, attempts)
        assertEquals("new", DesktopStorage.Store(file).getString("key"))
    }

    @Test fun permanentDenialPreservesFileAndRollsBackEveryMutationWithoutLeakingPendingFiles() = files { directory ->
        val file = directory.resolve("preferences.properties")
        val original = DesktopStorage.Store(file)
        original.putString("one", "A"); original.putString("two", "B")
        val bytes = Files.readAllBytes(file)
        val delays = mutableListOf<Long>()
        val store = DesktopStorage.Store(file) { source, target ->
            publishDesktopPreferenceFile(source, target,
                move = { _, _, _ -> throw AccessDeniedException(target.toString()) },
                pause = { delays += it }, retryAccessDenied = true)
        }
        assertFailsWith<AccessDeniedException> { store.putString("one", "changed") }
        assertFailsWith<AccessDeniedException> { store.remove("one") }
        assertFailsWith<AccessDeniedException> { store.removeAll(listOf("one", "two", "one")) }
        assertEquals("A", store.getString("one")); assertEquals("B", store.getString("two"))
        assertTrue(bytes.contentEquals(Files.readAllBytes(file)))
        assertEquals(List(3) { listOf(20L, 40L, 80L) }.flatten(), delays)
        Files.list(directory).use { assertEquals(listOf(file), it.toList()) }
    }

    @Test fun unsupportedAtomicMoveFallsBackButOtherIoFailuresAreNotRetried() = files { directory ->
        val source = directory.resolve("pending.part")
        val target = directory.resolve("preferences.properties")
        Files.writeString(source, "new")
        val attempts = mutableListOf<Boolean>()
        publishDesktopPreferenceFile(source, target, move = { from, to, atomic ->
            attempts += atomic
            if (atomic) throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "fixture")
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }, pause = { error("Atomic fallback must not sleep") })
        assertEquals(listOf(true, false), attempts)
        assertEquals("new", Files.readString(target))
        Files.writeString(source, "pending")
        assertFailsWith<java.io.IOException> {
            publishDesktopPreferenceFile(source, target,
                move = { _, _, _ -> throw java.io.IOException("Permanent storage failure") },
                pause = { error("Other IO errors must not retry") }, retryAccessDenied = true)
        }
        assertEquals("new", Files.readString(target))
    }

    @Test fun unreadableDocumentCannotBeSilentlyReplacedWithAnEmptyPreferenceSet() = files { directory ->
        val file = directory.resolve("preferences.properties")
        val invalid = "key=\\uZZZZ".toByteArray()
        Files.write(file, invalid)
        val store = DesktopStorage.Store(file)
        repeat(2) { assertFailsWith<IllegalArgumentException> { store.putString("other", "unsafe") } }
        assertTrue(invalid.contentEquals(Files.readAllBytes(file)))
    }

    @Test fun windowsReaderDenyingDeleteIsClosedBeforeTheBoundedRetryReplacesTheFile() = files { directory ->
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@files
        val target = directory.resolve("preferences.properties")
        val source = directory.resolve("pending.part")
        Files.writeString(target, "old"); Files.writeString(source, "new")
        val reader = Files.newByteChannel(target, setOf(StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE))
        var retried = false
        try {
            publishDesktopPreferenceFile(source, target, pause = {
                retried = true
                assertEquals("old", Files.readString(target))
                reader.close()
            })
            assertTrue(retried)
            assertEquals("new", Files.readString(target))
        } finally { reader.close() }
    }

    private fun files(action: (java.nio.file.Path) -> Unit) {
        val directory = Files.createTempDirectory("telumia-preference-publication-")
        try { action(directory) }
        finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
