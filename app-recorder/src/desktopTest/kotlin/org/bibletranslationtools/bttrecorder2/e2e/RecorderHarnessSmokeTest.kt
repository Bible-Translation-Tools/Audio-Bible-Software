package org.bibletranslationtools.bttrecorder2.e2e

import org.bibletranslationtools.bttrecorder2.e2e.harness.RecorderUiTestHarness
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.koin.mp.KoinPlatform.getKoin
import kotlin.test.Test
import kotlin.test.assertNotNull

class RecorderHarnessSmokeTest {
    @Test
    fun harnessStartsAndStops() {
        val root = RecorderUiTestHarness.start()
        try {
            assertNotNull(root)
            assertNotNull(getKoin().get<InitializeApp>())
        } finally {
            RecorderUiTestHarness.stop()
        }
    }
}
