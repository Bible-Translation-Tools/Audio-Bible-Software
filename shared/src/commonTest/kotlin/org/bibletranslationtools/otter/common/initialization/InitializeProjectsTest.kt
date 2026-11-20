package org.bibletranslationtools.otter.common.initialization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporter
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class InitializeProjectsTest {

    private val userData: File = Files.createTempDirectory("projects").toFile()

    private val metadataRepo = mockk<IResourceMetadataRepository> {
        coEvery { getAllSourcesSuspend() } returns emptyList()
    }
    private val takeRepo = mockk<ITakeRepository>(relaxUnitFun = true) {
        coEvery { getAllSuspend() } returns emptyList()
    }
    private val directories = mockk<IDirectoryProvider> {
        every { getUserDataDirectory(any()) } returns userData
    }
    private val installed = mockk<IInstalledEntityRepository>(relaxUnitFun = true)
    private val workbooks = mockk<IWorkbookRepository> {
        coEvery { getProjectsSuspend() } returns emptyList()
    }
    private val importer = mockk<RCImporter> {
        coEvery { importSuspend(any(), any(), any()) } returns ImportResult.SUCCESS
    }
    private val initializer = InitializeProjects(
        metadataRepo,
        takeRepo,
        directories,
        installed,
        workbooks,
        mockk<RCImporterFactory> { every { makeImporter() } returns importer }
    )

    private fun installedVersion(version: Int?) {
        every { installed.getInstalledVersion(any()) } returns version
    }

    private fun projectFolder(name: String): File =
        File(userData, name).apply {
            mkdirs()
            File(this, "manifest.yaml").writeText("manifest")
        }

    @AfterTest
    fun cleanup() {
        userData.deleteRecursively()
    }

    @Test
    fun `with projects in the database nothing is imported`() = runTest {
        installedVersion(initializer.version)
        coEvery { workbooks.getProjectsSuspend() } returns listOf(mockk<Workbook>())
        projectFolder("en_ulb_gen")

        initializer.execSuspend {}

        coVerify(exactly = 0) { importer.importSuspend(any(), any(), any()) }
        verify(exactly = 0) { installed.install(any()) }
    }

    /** As the Rx version behaved: one project that fails to import does not stop the others. */
    @Test
    fun `with no projects every project folder is imported, a failed one included`() = runTest {
        installedVersion(initializer.version)
        val failing = projectFolder("en_ulb_gen")
        val other = projectFolder("en_ulb_exo")
        coEvery { importer.importSuspend(failing, any(), any()) } throws IllegalStateException("corrupt")

        initializer.execSuspend {}

        coVerify(exactly = 1) { importer.importSuspend(failing, any(), any()) }
        coVerify(exactly = 1) { importer.importSuspend(other, any(), any()) }
    }

    /** The Rx version read this Completable with blockingGet(), which hid the failure. */
    @Test
    fun `a take that cannot be updated fails the migration and records nothing`() = runTest {
        installedVersion(null)
        val takeFile = File(userData, "en_ulb_gen/c01/take.wav").apply {
            parentFile.mkdirs()
            writeText("audio")
        }
        val take = Take("take.wav", takeFile, 1, LocalDate.now(), null, false, CheckingStatus.UNCHECKED, null, listOf())
        coEvery { takeRepo.getAllSuspend() } returns listOf(take)
        coEvery { takeRepo.updateSuspend(any()) } throws IllegalStateException("database is locked")

        assertFailsWith<IllegalStateException> { initializer.execSuspend {} }

        verify(exactly = 0) { installed.install(any()) }
    }

    @Test
    fun `the Rx exec does the same work`() {
        installedVersion(null)
        val project = projectFolder("en_ulb_gen")

        initializer.exec(mockk(relaxed = true)).blockingAwait()

        verify(exactly = 1) { installed.install(initializer) }
        coVerify(exactly = 1) { importer.importSuspend(project, any(), any()) }
    }
}
