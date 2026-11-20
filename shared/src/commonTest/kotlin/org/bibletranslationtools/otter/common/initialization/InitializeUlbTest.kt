package org.bibletranslationtools.otter.common.initialization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.project.BundledSourceStamps
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportException
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InitializeUlbTest {

    private val tempFile: File = Files.createTempFile("en_ulb-default", ".zip").toFile()

    private val tempFiles = mockk<ITempFileProvider> {
        every { createTempFile(any(), any()) } returns tempFile
    }
    private val installed = mockk<IInstalledEntityRepository>(relaxUnitFun = true) {
        every { getInstalledVersion(any()) } returns null
    }
    private val importer = mockk<ImportProjectUseCase> {
        coEvery { isAlreadyImportedSuspend(any()) } returns false
        coEvery { importSuspend(any(), any(), any()) } returns ImportResult.SUCCESS
    }

    // Only the suspending read is stubbed, so a blocking read would fail the test.
    private val bundled = mockk<IBundledContentSource> {
        coEvery { read(any()) } returns "bundled".encodeToByteArray()
    }
    private val stamps = mockk<BundledSourceStamps>(relaxUnitFun = true)
    private val initializer = InitializeUlb(tempFiles, installed, importer, bundled, stamps)

    @AfterTest
    fun cleanup() {
        tempFile.delete()
    }

    @Test
    fun `a fresh install imports the bundled source, records it and forwards progress`() = runTest {
        coEvery { importer.importSuspend(any(), any(), any()) } coAnswers {
            secondArg<ProjectImporterCallback?>()?.onNotifyProgress("importingSource", "en_ulb")
            ImportResult.SUCCESS
        }
        val progress = mutableListOf<ProgressStatus>()

        initializer.execSuspend { progress += it }

        assertEquals("bundled", tempFile.readText())
        coVerify(exactly = 1) { importer.importSuspend(tempFile, any(), any()) }
        verify(exactly = 1) { installed.install(initializer) }
        verify(exactly = 1) { stamps.markImported(EN_ULB_FILENAME) }
        assertEquals(
            listOf(
                ProgressStatus(
                    titleKey = "initializingSources",
                    subTitleKey = "loadingSomething",
                    subTitleMessage = "EN_ULB"
                ),
                ProgressStatus(subTitleKey = "importingSource", subTitleMessage = "en_ulb")
            ),
            progress
        )
    }

    @Test
    fun `the current version does nothing`() = runTest {
        every { installed.getInstalledVersion(any()) } returns initializer.version

        initializer.execSuspend {}

        coVerify(exactly = 0) { bundled.read(any()) }
        coVerify(exactly = 0) { importer.importSuspend(any(), any(), any()) }
        verify(exactly = 0) { installed.install(any()) }
    }

    /** As the Rx version behaved: the stamp is marked, the step's own version is not. */
    @Test
    fun `a source that is already installed is not imported again`() = runTest {
        coEvery { importer.isAlreadyImportedSuspend(any()) } returns true

        initializer.execSuspend {}

        coVerify(exactly = 0) { importer.importSuspend(any(), any(), any()) }
        verify(exactly = 1) { stamps.markImported(EN_ULB_FILENAME) }
        verify(exactly = 0) { installed.install(any()) }
    }

    /** The Rx version threw this inside blockingSubscribe, where it never reached the caller. */
    @Test
    fun `a failed import fails the step and records nothing`() = runTest {
        coEvery { importer.importSuspend(any(), any(), any()) } returns ImportResult.IMPORT_ERROR

        val error = assertFailsWith<ImportException> { initializer.execSuspend {} }

        assertEquals(ImportResult.IMPORT_ERROR, error.result)
        verify(exactly = 0) { installed.install(any()) }
        verify(exactly = 0) { stamps.markImported(any()) }
    }
}
