package org.bibletranslationtools.otter.common.initialization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.domain.project.BundledSourceStamps
import org.bibletranslationtools.otter.common.domain.project.GlSourceCatalog
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.ResourceInfoSerializable
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledSourceEditions
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class RefreshBundledSourcesTest {

    private val temp: File = Files.createTempDirectory("refresh").toFile()

    private val catalog = mockk<GlSourceCatalog> {
        every { embeddedSourceChecksums } returns mapOf("en_ulb" to "abc1234", "es_ulb" to "def5678")
        every { sources } returns listOf(
            ResourceInfoSerializable("en_ulb", "en", "https://example.org/en_ulb.zip"),
            ResourceInfoSerializable("es_ulb", "es", "https://example.org/es_ulb.zip")
        )
    }
    private val editions = mockk<InstalledSourceEditions> {
        coEvery { editionsOfNoRx(any(), any()) } returns listOf(mockk())
    }
    private val importer = mockk<ImportProjectUseCase> {
        coEvery { importSuspend(any(), any(), any()) } returns ImportResult.SUCCESS
    }
    private val stamps = mockk<BundledSourceStamps>(relaxUnitFun = true) {
        every { isCurrent(any()) } returns false
    }

    // Only the suspending read is stubbed, so a blocking read would fail the test.
    private val bundled = mockk<IBundledContentSource> {
        coEvery { read(any()) } returns "zip".encodeToByteArray()
    }
    private val tempFiles = mockk<ITempFileProvider> {
        every { createTempFile(any(), any()) } answers { File.createTempFile(firstArg(), secondArg(), temp) }
    }
    private val initializer = RefreshBundledSources(catalog, editions, importer, stamps, bundled, tempFiles)

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    @Test
    fun `a changed bundle of an installed source is imported, recorded and its temp file removed`() = runTest {
        initializer.exec {}

        coVerify(exactly = 2) { importer.importSuspend(any(), null, any()) }
        verify(exactly = 1) { stamps.markImported("en_ulb") }
        verify(exactly = 1) { stamps.markImported("es_ulb") }
        assertTrue(temp.list().isNullOrEmpty())
    }

    @Test
    fun `an unchanged bundle is skipped`() = runTest {
        every { stamps.isCurrent("en_ulb") } returns true

        initializer.exec {}

        coVerify(exactly = 1) { importer.importSuspend(any(), any(), any()) }
        verify(exactly = 0) { stamps.markImported("en_ulb") }
    }

    @Test
    fun `a source with no edition installed is skipped`() = runTest {
        coEvery { editions.editionsOfNoRx("en", "ulb") } returns emptyList()

        initializer.exec {}

        coVerify(exactly = 1) { importer.importSuspend(any(), any(), any()) }
        verify(exactly = 0) { stamps.markImported("en_ulb") }
        verify(exactly = 1) { stamps.markImported("es_ulb") }
    }

    /** Never fails initialization: it is logged, not recorded, and tried again next launch. */
    @Test
    fun `a source that cannot be refreshed is not recorded and the rest go on`() = runTest {
        coEvery { importer.importSuspend(any(), any(), any()) } returns ImportResult.IMPORT_ERROR andThenThrows
            IllegalStateException("disk full")

        initializer.exec {}

        coVerify(exactly = 2) { importer.importSuspend(any(), any(), any()) }
        verify(exactly = 0) { stamps.markImported(any()) }
    }
}
