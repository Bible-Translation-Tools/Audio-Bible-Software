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
        coEvery { insertAll(any()) } returns listOf(1)
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

        initializer.exec {}

        coVerifyOrder {
            languages.insertAll(match { it.single().slug == "en" })
            languages.updateRegions(match { it.single().region == "Europe" })
        }
        verify(exactly = 1) { installed.install(initializer) }
    }

    @Test
    fun `version 1 only gets its regions updated`() = runTest {
        installedVersion(1)

        initializer.exec {}

        coVerify(exactly = 0) { languages.insertAll(any()) }
        coVerify(exactly = 1) { languages.updateRegions(any()) }
        verify(exactly = 1) { installed.install(initializer) }
    }

    @Test
    fun `the current version does nothing`() = runTest {
        installedVersion(initializer.version)

        initializer.exec {}

        coVerify(exactly = 0) { bundled.read(any()) }
        verify(exactly = 0) { installed.install(any()) }
    }

    @Test
    fun `a failed import fails the step and records nothing`() = runTest {
        installedVersion(null)
        coEvery { languages.insertAll(any()) } throws IllegalStateException("insert failed")

        assertFailsWith<IllegalStateException> { initializer.exec {} }

        verify(exactly = 0) { installed.install(any()) }
    }

    /** The Rx version hid this failure and recorded the version anyway. */
    @Test
    fun `a failed region update fails the step and records nothing`() = runTest {
        installedVersion(1)
        coEvery { languages.updateRegions(any()) } throws IllegalStateException("update failed")

        assertFailsWith<IllegalStateException> { initializer.exec {} }

        verify(exactly = 0) { installed.install(any()) }
    }
}
