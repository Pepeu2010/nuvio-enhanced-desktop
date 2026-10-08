package com.nuvio.app.features.home.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HomePosterPreviewOwnershipTest {
    @Test fun disposalOfAnOldCardCannotReleaseTheNewCard() {
        val owner = HomePosterPreviewOwnership()
        val first = Any(); val second = Any()
        owner.claim(first); owner.claim(second); owner.release(first)
        assertSame(second, owner.active.value)
        owner.release(second)
        assertNull(owner.active.value)
    }

    @Test fun aMountedCopyOfTheSameContentStillNeedsItsOwnToken() {
        val owner = HomePosterPreviewOwnership()
        val firstCopy = Any(); val secondCopy = Any()
        owner.claim(firstCopy); owner.release(secondCopy)
        assertSame(firstCopy, owner.active.value)
        owner.claim(secondCopy); owner.release(firstCopy)
        assertSame(secondCopy, owner.active.value)
    }

    @Test fun unavailableMetadataAndResolutionErrorsLeaveAStaticCard() = runBlocking {
        assertNull(resolveBoundedHoverPreview<String> { null })
        assertNull(resolveBoundedHoverPreview<String> { throw IllegalStateException("fixture") })
        assertEquals("available", resolveBoundedHoverPreview { "available" })
    }

    @Test fun aSlowResolverHasOneDeadlineAndRunsItsCleanup() = runBlocking {
        var cleanedUp = false
        val value = withTimeout(2_000) {
            resolveBoundedHoverPreview<String>(timeoutMillis = 30) {
                try { awaitCancellation() } finally { cleanedUp = true }
            }
        }
        assertNull(value)
        assertTrue(cleanedUp)
    }

    @Test fun leavingTheCardCancelsTheResolverRatherThanConvertingCancellationToAFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cleanedUp = false
        val request = async {
            resolveBoundedHoverPreview<String> {
                started.complete(Unit)
                try { awaitCancellation() } finally { cleanedUp = true }
            }
        }
        withTimeout(2_000) { started.await() }
        request.cancel()
        assertFailsWith<CancellationException> { request.await() }
        assertTrue(cleanedUp)
    }
}
