package org.bibletranslationtools.otter.common.initialization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.IResourceContainerDirectories
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporter
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InitializeSourcesTest {

    private val dir: File = Files.createTempDirectory("sources").toFile()
    private val newSource = File(dir, "en_ulb.zip").apply { writeText("new") }
    private val installedSource = File(dir, "es_ulb.zip").apply { writeText("installed") }

    private val directories = mockk<IResourceContainerDirectories> {
        every { internalSourceRCDirectory } returns dir
    }
    private val metadataRepo = mockk<IResourceMetadataRepository> {
        coEvery { getAllSourcesSuspend() } returns
            listOf(mockk<ResourceMetadata> { every { path } returns installedSource })
    }
    private val installed = mockk<IInstalledEntityRepository>(relaxUnitFun = true) {
        every { getInstalledVersion(any()) } returns null
    }
    private val importer = mockk<RCImporter> {
        coEvery { importSuspend(any(), any(), any()) } returns ImportResult.SUCCESS
    }
    private val initializer = InitializeSources(
        directories,
        metadataRepo,
        installed,
        mockk<RCImporterFactory> { every { makeImporter() } returns importer }
    )

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `imports the source files that are not installed yet and records the version`() = runTest {
        File(dir, "notes.txt").writeText("not a source")
        coEvery { importer.importSuspend(any(), any(), any()) } coAnswers {
            secondArg<ProjectImporterCallback?>()?.onNotifyProgress("loadingSomething", "en_ulb")
            ImportResult.SUCCESS
        }
        val progress = mutableListOf<ProgressStatus>()

        initializer.execSuspend { progress += it }

        coVerify(exactly = 1) { importer.importSuspend(any(), any(), any()) }
        coVerify(exactly = 1) { importer.importSuspend(newSource, any(), any()) }
        assertEquals(
            listOf(
                ProgressStatus(titleKey = "initializingSources"),
                ProgressStatus(subTitleKey = "loadingSomething", subTitleMessage = "en_ulb")
            ),
            progress
        )
        verify(exactly = 1) { installed.install(initializer) }
    }

    @Test
    fun `the current version does nothing`() = runTest {
        every { installed.getInstalledVersion(any()) } returns initializer.version

        initializer.execSuspend {}

        coVerify(exactly = 0) { importer.importSuspend(any(), any(), any()) }
        verify(exactly = 0) { installed.install(any()) }
    }

    /** As the Rx version behaved: a failed import was logged and startup carried on. */
    @Test
    fun `a source that fails to import does not stop the others or the version stamp`() = runTest {
        val other = File(dir, "fr_ulb.orature").apply { writeText("other") }
        coEvery { importer.importSuspend(newSource, any(), any()) } throws IllegalStateException("corrupt")

        initializer.execSuspend {}

        coVerify(exactly = 1) { importer.importSuspend(other, any(), any()) }
        verify(exactly = 1) { installed.install(initializer) }
    }

    @Test
    fun `the Rx exec does the same work`() {
        initializer.exec(mockk(relaxed = true)).blockingAwait()

        coVerify(exactly = 1) { importer.importSuspend(newSource, any(), any()) }
        verify(exactly = 1) { installed.install(initializer) }
    }
}
