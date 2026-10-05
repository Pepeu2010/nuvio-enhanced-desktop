package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ForkUpdateSourceTest {
    @Test
    fun desktopUpdatesComeOnlyFromIndependentFork() {
        assertEquals("Pepeu2010", AppUpdaterPlatform.releaseSource.owner)
        assertEquals("telumia-desktop", AppUpdaterPlatform.releaseSource.repo)
        assertNotEquals("NuvioMedia", AppUpdaterPlatform.releaseSource.owner)
    }
}
