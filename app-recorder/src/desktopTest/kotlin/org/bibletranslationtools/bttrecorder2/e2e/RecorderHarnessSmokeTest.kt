package org.bibletranslationtools.bttrecorder2.e2e

import org.bibletranslationtools.bttrecorder2.e2e.harness.RecorderUiTestHarness
import org.bibletranslationtools.bttrecorder2.exports.WriteNarrationForExport
import org.bibletranslationtools.bttrecorder2.imports.ImportNarrationAsTakes
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
            // The ViewModels inject these lazily, so a graph missing them fails only once a test
            // reaches an import or an export. Resolving them here fails at the graph instead.
            assertNotNull(getKoin().get<ImportNarrationAsTakes>())
            assertNotNull(getKoin().get<WriteNarrationForExport>())
        } finally {
            RecorderUiTestHarness.stop()
        }
    }
}
