package org.bibletranslationtools.bttrecorder2.legacymigration

import org.bibletranslationtools.bttrecorder2.di.koin.recorderViewModelModule
import org.bibletranslationtools.otter.common.api.persistence.config.AppStartupStep
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Only the Android recorder migrates old BTT-Recorder projects; the desktop graph has none of it. */
class DesktopGraphHasNoMigrationTest {

    private val koin = koinApplication { modules(sharedCommonModules + recorderViewModelModule) }.koin

    @Test
    fun `the desktop graph has no startup step of the app's own`() {
        assertTrue(koin.getAll<AppStartupStep>().isEmpty())
    }

    @Test
    fun `the desktop graph has no migration summary to show`() {
        assertNull(koin.getOrNull<ILegacyMigrationSummaries>())
    }
}
