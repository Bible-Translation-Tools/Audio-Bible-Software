package org.bibletranslationtools.otter.common.initialization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InitializeAppTest {

    private val versification = mockk<InitializeVersification>(relaxUnitFun = true)
    private val sources = mockk<InitializeSources>(relaxUnitFun = true)
    private val languages = mockk<InitializeLanguages>(relaxUnitFun = true)
    private val ulb = mockk<InitializeUlb>(relaxUnitFun = true)
    private val takes = mockk<InitializeTakeRepository>(relaxUnitFun = true)
    private val projects = mockk<InitializeProjects>(relaxUnitFun = true)
    private val translations = mockk<InitializeTranslations>(relaxUnitFun = true)
    private val fingerprints = mockk<BackfillEditionFingerprints>(relaxUnitFun = true)
    private val labels = mockk<ReconcileEditionLabels>(relaxUnitFun = true)
    private val bundled = mockk<RefreshBundledSources>(relaxUnitFun = true)
    private val audit = mockk<AuditSourceStructure>(relaxUnitFun = true)

    private val tempFiles = mockk<ITempFileProvider>(relaxUnitFun = true)
    private val daoProvider = mockk<DaoProvider> {
        coEvery { withBulkLoad(any<suspend () -> Unit>()) } coAnswers { firstArg<suspend () -> Unit>().invoke() }
    }

    private val initApp = InitializeApp(
        versification, sources, languages, ulb, takes, projects, translations,
        fingerprints, labels, bundled, audit, tempFiles, daoProvider
    )

    @Test
    fun `runs every step in order, reports progress and cleans the temp directory`() = runTest {
        val progress = mutableListOf<ProgressStatus>()

        initApp.initApp { progress += it }

        coVerifyOrder {
            versification.exec(any())
            languages.exec(any())
            sources.exec(any())
            ulb.exec(any())
            takes.exec(any())
            projects.exec(any())
            translations.exec(any())
            fingerprints.exec(any())
            labels.exec(any())
            bundled.exec(any())
            audit.exec(any())
        }
        assertEquals(11, progress.size)
        assertEquals(1.0, progress.last().percent!!, 0.0001)
        verify(exactly = 1) { tempFiles.cleanTempDirectory() }
    }

    @Test
    fun `the first failing step stops the rest, and the temp directory is still cleaned`() = runTest {
        coEvery { sources.exec(any()) } throws IllegalStateException("database is locked")

        assertFailsWith<IllegalStateException> { initApp.initApp {} }

        coVerify(exactly = 1) { languages.exec(any()) }
        coVerify(exactly = 0) { ulb.exec(any()) }
        verify(exactly = 1) { tempFiles.cleanTempDirectory() }
    }
}
