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
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporter
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.io.zip.NioZipFileReader
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitializeProjectsTest {

    private val userData: File = Files.createTempDirectory("projects").toFile()

    private val metadataRepo = mockk<IResourceMetadataRepository> {
        coEvery { getAllSourcesNoRx() } returns emptyList()
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

        initializer.exec {}

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

        initializer.exec {}

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

        assertFailsWith<IllegalStateException> { initializer.exec {} }

        verify(exactly = 0) { installed.install(any()) }
    }

    /** Version 2 keeps sources unpacked: the zip is replaced by a folder and the stored path follows. */
    @Test
    fun `a source stored as a zip is unpacked beside it, the zip removed and its path updated`() = runTest {
        installedVersion(null)
        val sources = File(userData, "sources").apply { mkdirs() }
        val zip = File(sources, "en_ulb.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("en_ulb/manifest.yaml")); out.write("manifest".toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("en_ulb/content/gen.usfm")); out.write("text".toByteArray()); out.closeEntry()
        }
        val source = ResourceMetadata(
            conformsTo = "rc0.2", creator = "Door43", description = "", format = "text/usfm", identifier = "ulb",
            issued = LocalDate.now(), language = Language("en", "English", "English", "ltr", true, "Europe"),
            modified = LocalDate.now(), publisher = "", subject = "", type = ContainerType.Book, title = "ULB",
            version = "1", license = "", path = zip
        )
        coEvery { metadataRepo.getAllSourcesNoRx() } returns listOf(source)
        coEvery { metadataRepo.updateSuspend(any<ResourceMetadata>()) } returns Unit
        every { directories.newFileReader(any()) } answers { NioZipFileReader(firstArg()) }
        val unpacked = File(sources, "en_ulb-source")

        initializer.exec {}

        assertEquals("manifest", File(unpacked, "manifest.yaml").readText())
        assertEquals("text", File(unpacked, "content/gen.usfm").readText())
        assertFalse(zip.exists(), "the zip is removed once it is unpacked")
        coVerify(exactly = 1) { metadataRepo.updateSuspend(match<ResourceMetadata> { it.path == unpacked && it.identifier == "ulb" }) }
        verify(exactly = 1) { installed.install(initializer) }
        assertTrue(unpacked.isDirectory)
    }

}
