package org.bibletranslationtools.otter.common.initialization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import kotlin.test.Test
import kotlin.test.assertFailsWith

class InitializeTranslationsTest {

    private val english = mockk<Language>()
    private val french = mockk<Language>()
    private val spanish = mockk<Language>()

    private val installed = mockk<IInstalledEntityRepository>(relaxUnitFun = true) {
        every { getInstalledVersion(any()) } returns null
    }
    private val workbooks = mockk<IWorkbookRepository> {
        coEvery { getProjectsSuspend() } returns listOf(project(english, french), project(english, spanish))
    }
    private val languages = mockk<ILanguageRepository> {
        coEvery { insertTranslationSuspend(any()) } returns 1
    }
    private val initializer = InitializeTranslations(installed, workbooks, languages)

    private fun project(source: Language, target: Language): Workbook = mockk {
        every { this@mockk.source.language } returns source
        every { this@mockk.target.language } returns target
    }

    @Test
    fun `a fresh install records a translation for every project and then the version`() = runTest {
        initializer.execSuspend {}

        coVerify(exactly = 1) { languages.insertTranslationSuspend(match { it.source == english && it.target == french }) }
        coVerify(exactly = 1) { languages.insertTranslationSuspend(match { it.source == english && it.target == spanish }) }
        verify(exactly = 1) { installed.install(initializer) }
    }

    @Test
    fun `the current version does nothing`() = runTest {
        every { installed.getInstalledVersion(any()) } returns initializer.version

        initializer.execSuspend {}

        coVerify(exactly = 0) { workbooks.getProjectsSuspend() }
        verify(exactly = 0) { installed.install(any()) }
    }

    /** As the Rx version was written (onErrorReturnItem): the failure is logged and the rest goes on. */
    @Test
    fun `a translation that cannot be written does not stop the others or the version`() = runTest {
        coEvery {
            languages.insertTranslationSuspend(match { it.target == french })
        } throws IllegalStateException("database is locked")

        initializer.execSuspend {}

        coVerify(exactly = 1) { languages.insertTranslationSuspend(match { it.target == spanish }) }
        verify(exactly = 1) { installed.install(initializer) }
    }

    @Test
    fun `projects that cannot be loaded fail the step and record nothing`() = runTest {
        coEvery { workbooks.getProjectsSuspend() } throws IllegalStateException("database is locked")

        assertFailsWith<IllegalStateException> { initializer.execSuspend {} }

        verify(exactly = 0) { installed.install(any()) }
    }

    @Test
    fun `the Rx exec does the same work`() {
        initializer.exec(mockk()).blockingAwait()

        coVerify(exactly = 2) { languages.insertTranslationSuspend(any()) }
        verify(exactly = 1) { installed.install(initializer) }
    }
}
