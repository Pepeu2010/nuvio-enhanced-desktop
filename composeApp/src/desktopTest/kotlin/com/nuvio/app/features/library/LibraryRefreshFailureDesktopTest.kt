package com.nuvio.app.features.library

import com.nuvio.app.features.library.sync.*
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LibraryRefreshFailureDesktopTest {
    @Test fun coordinatorStrictPullReceivesFailureAndTheNextAttemptCanRecover() = runBlocking {
        check(System.getenv("NUVIO_ENHANCED_ISOLATED_THEME_TEST") == "1")
        val index = ProfileRepository::class.java.getDeclaredField("activeProfileIndex").apply { isAccessible = true }
        val previousIndex = index.getInt(ProfileRepository)
        index.setInt(ProfileRepository, 1)
        TrackingSettingsRepository.ensureLoaded()
        val previousSource = TrackingSettingsRepository.uiState.value.librarySourceMode
        val previousAdapter = LibraryRepository.syncAdapter
        val previousPayload = LibraryStorage.loadPayload(1)
        var offline = true
        var attempts = 0
        val adapter = object : LibrarySyncAdapter {
            override suspend fun getDeltaCursor(profileId: Int): Long { attempts++; check(!offline) { "Offline fixture" }; return 0 }
            override suspend fun pullSnapshot(profileId: Int, pageSize: Int): List<LibraryItem> = emptyList()
            override suspend fun pullDelta(profileId: Int, sinceEventId: Long, limit: Int): List<LibraryDeltaEvent> = emptyList()
            override suspend fun pushItems(profileId: Int, items: Collection<LibraryItem>) = Unit
            override suspend fun deleteItems(profileId: Int, keys: Collection<LibrarySyncKey>) = Unit
        }
        try {
            TrackingSettingsRepository.setLibrarySourceMode(LibrarySourceMode.LOCAL)
            LibraryStorage.savePayload(1, "")
            LibraryRepository.clearLocalState()
            LibraryRepository.syncAdapter = adapter
            LibraryRepository.pullFromServer(1) // Existing UI callers preserve their best-effort behavior.
            assertEquals(1, attempts)
            assertFailsWith<IllegalStateException> { LibraryRepository.pullFromServer(1, throwOnFailure = true) }
            assertEquals(2, attempts)
            offline = false
            LibraryRepository.pullFromServer(1, throwOnFailure = true)
            assertEquals(3, attempts)
            assertTrue(LibraryRepository.uiState.value.items.isEmpty())
        } finally {
            LibraryRepository.clearLocalState()
            LibraryRepository.syncAdapter = previousAdapter
            LibraryStorage.savePayload(1, previousPayload ?: "")
            TrackingSettingsRepository.setLibrarySourceMode(previousSource)
            index.setInt(ProfileRepository, previousIndex)
        }
    }
}
