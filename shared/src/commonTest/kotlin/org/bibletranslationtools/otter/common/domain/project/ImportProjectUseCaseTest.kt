package org.bibletranslationtools.otter.common.domain.project

import io.mockk.mockk
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A file that matches no known project format is reported as UNSUPPORTED_CONTENT rather than the
 * blanket FAILED. When no identifier matches, ProjectFormatIdentifier.getProjectFormat throws
 * IllegalArgumentException; the use case maps that (and the "recognised but no importer" case) to
 * UNSUPPORTED_CONTENT via ImportException.
 *
 * The use case's collaborators are never reached for an unrecognised file — format identification
 * fails first — so relaxed mocks are enough to construct it.
 */
class ImportProjectUseCaseTest {

    private lateinit var tmpDir: File

    private fun useCase() = ImportProjectUseCase(
        burritoFactoryProvider = mockk(relaxed = true),
        rcFactoryProvider = mockk(relaxed = true),
        tsFactoryProvider = mockk(relaxed = true),
        rcImporter = mockk(relaxed = true),
        fileIO = mockk(relaxed = true),
        tempFiles = mockk(relaxed = true),
        bundledContent = mockk(relaxed = true),
        glSourceCatalog = mockk(relaxed = true),
    )

    @Test
    fun unsupportedFileImportsAsUnsupportedContent() {
        val file = File.createTempFile("not-a-project", ".txt", tmpDir)
        file.writeText("this is not any supported project format")

        assertEquals(ImportResult.UNSUPPORTED_CONTENT, useCase().import(file).blockingGet())
    }

    @Test
    fun zipWithoutAKnownManifestImportsAsUnsupportedContent() {
        val zip = File.createTempFile("junk", ".zip", tmpDir)
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("nothing a format identifier should recognise".toByteArray())
            zos.closeEntry()
        }

        assertEquals(ImportResult.UNSUPPORTED_CONTENT, useCase().import(zip).blockingGet())
    }

    @BeforeTest
    fun setup() {
        tmpDir = createTempDirectory("import-usecase").toFile()
    }

    @AfterTest
    fun cleanUp() {
        tmpDir.deleteRecursively()
    }
}
