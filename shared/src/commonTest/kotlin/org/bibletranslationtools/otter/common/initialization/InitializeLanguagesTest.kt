package org.bibletranslationtools.otter.common.initialization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import kotlin.test.Test
import kotlin.test.assertFailsWith

class InitializeLanguagesTest {

    private val langNames =
        """[{"pk":1,"lc":"en","ln":"English","ld":"ltr","gw":true,"ang":"English","lr":"Europe"}]"""

    private val installed = mockk<IInstalledEntityRepository>(relaxUnitFun = true)
    private val languages = mockk<ILanguageRepository>(relaxUnitFun = true) {
        coEvery { insertAllSuspend(any()) } returns listOf(1)
    }

    // Only the suspending read is stubbed, so a blocking read would fail the test.
    private val bundled = mockk<IBundledContentSource> {
        coEvery { read(LANGNAMES_PATH) } returns langNames.encodeToByteArray()
    }
    private val initializer = InitializeLanguages(installed, languages, mockk(), bundled)

    private fun installedVersion(version: Int?) {
        every { installed.getInstalledVersion(any()) } returns version
    }

    @Test
    fun `a fresh install imports the languages, then their regions, and records the version`() = runTest {
        installedVersion(null)

        initializer.execSuspend {}

        coVerifyOrder {
            languages.insertAllSuspend(match { it.single().slug == "en" })
            languages.updateRegionsSuspend(match { it.single().region == "Europe" })
        }
        verify(exactly = 1) { installed.install(initializer) }
    }

    @Test
    fun `version 1 only gets its regions updated`() = runTest {
        installedVersion(1)

        initializer.execSuspend {}

        coVerify(exactly = 0) { languages.insertAllSuspend(any()) }
        coVerify(exactly = 1) { languages.updateRegionsSuspend(any()) }
        verify(exactly = 1) { installed.install(initializer) }
    }

    @Test
    fun `the current version does nothing`() = runTest {
        installedVersion(initializer.version)

        initializer.execSuspend {}

        coVerify(exactly = 0) { bundled.read(any()) }
        verify(exactly = 0) { installed.install(any()) }
    }

    @Test
    fun `a failed import fails the step and records nothing`() = runTest {
        installedVersion(null)
        coEvery { languages.insertAllSuspend(any()) } throws IllegalStateException("insert failed")

        assertFailsWith<IllegalStateException> { initializer.execSuspend {} }

        verify(exactly = 0) { installed.install(any()) }
    }

    /** As the Rx version behaved: a failed region update was never reported to the caller. */
    @Test
    fun `a failed region update still records the version`() = runTest {
        installedVersion(1)
        coEvery { languages.updateRegionsSuspend(any()) } throws IllegalStateException("update failed")

        initializer.execSuspend {}

        verify(exactly = 1) { installed.install(initializer) }
    }

    @Test
    fun `the Rx exec does the same work`() {
        installedVersion(null)

        initializer.exec(mockk()).blockingAwait()

        coVerify(exactly = 1) { languages.insertAllSuspend(any()) }
        verify(exactly = 1) { installed.install(initializer) }
    }
}
