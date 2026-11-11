package org.bibletranslationtools.otter.common

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import io.reactivex.Single
import io.reactivex.exceptions.OnErrorNotImplementedException
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.io.zip.IFileWriter
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.IResourceContainerDirectories
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.AudioConverter
import org.bibletranslationtools.otter.common.domain.languages.ImportLanguages
import org.bibletranslationtools.otter.common.domain.narration.PcmTakeTransformer
import org.bibletranslationtools.otter.common.domain.project.BundledSourceStamps
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportException
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import org.bibletranslationtools.otter.common.initialization.InitializeProjects
import org.bibletranslationtools.otter.common.initialization.InitializeSources
import org.bibletranslationtools.otter.common.initialization.InitializeTranslations
import org.bibletranslationtools.otter.common.initialization.InitializeUlb
import org.bibletranslationtools.otter.common.io.utils.copyDirectoryTo
import org.bibletranslationtools.otter.common.io.utils.copyFileTo
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.bibletranslationtools.otter.common.data.workbook.Take as WorkbookTake

/**
 * The places where an error is dropped today, each made to fail and its outcome pinned.
 *
 * These come from the RxJava migration audit: an error is dropped here by how an Rx call happens to
 * behave, not by a decision. `blockingSubscribe(onNext)` hands it to RxJava's global handler,
 * `Completable.blockingGet()` returns it as a value nobody reads, `onErrorReturnItem` swaps it for a
 * default. A port to `suspend` code would turn each into a thrown exception unless it is written to
 * do otherwise, so each outcome is recorded here first. A site that should fail loudly is changed
 * on purpose, in its own change, by changing its test.
 *
 * The sites inside the project importer are in `DroppedImportErrorsTest`, in the integration tier.
 */
class DroppedErrorsTest {

    @get:Rule
    val noStrayErrors = NoStrayErrorsRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val boom = IOException("injected")
    private val progress = mockk<ObservableEmitter<ProgressStatus>>(relaxed = true)
    private val installed = mockk<IInstalledEntityRepository>(relaxed = true) {
        every { getInstalledVersion(any()) } returns null
    }

    private val english = Language("en", "English", "English", "ltr", true, "Europe", 1)

    private fun metadata(path: File) = ResourceMetadata(
        "rc0.2", "creator", "", "text/usfm", "ulb", LocalDate.of(2024, 1, 1), english, LocalDate.of(2024, 1, 1),
        "", "Bible", ContainerType.Bundle, "ULB", "1", "", path, 1
    )

    /** What reached RxJava's global handler, unwrapped from "no error handler was given". */
    private fun strayCauses(): List<Throwable?> = noStrayErrors.drain().map {
        assertIs<OnErrorNotImplementedException>(it)
        it.cause
    }

    private fun failingImporters() = mockk<RCImporterFactory> {
        every { makeImporter().import(any(), any(), any()) } returns Single.error(boom)
    }

    // InitializeSources.importFile: blockingSubscribe(onNext)
    @Test
    fun `startup carries on and records the sources step when a source fails to import`() {
        val sources = temp.newFolder("sources").also { File(it, "en_ulb.zip").writeText("not a container") }
        val initializer = InitializeSources(
            mockk<IResourceContainerDirectories> { every { internalSourceRCDirectory } returns sources },
            mockk<IResourceMetadataRepository> { every { getAllSources() } returns Single.just(emptyList()) },
            installed,
            failingImporters()
        )

        initializer.exec(progress).blockingAwait()

        verify { installed.install(initializer) }
        assertEquals(listOf<Throwable?>(boom), strayCauses(), "the failure goes to the global handler")
    }

    // InitializeUlb.exec: throw inside blockingSubscribe(onNext)
    @Test
    fun `startup carries on and leaves the ULB step to be retried when the bundled ULB fails to import`() {
        val importer = mockk<ImportProjectUseCase> {
            every { isAlreadyImported(any()) } returns false
            every { import(any(), any(), any()) } returns Single.just(ImportResult.IMPORT_ERROR)
        }
        val stamps = mockk<BundledSourceStamps>(relaxed = true)
        val initializer = InitializeUlb(
            mockk<ITempFileProvider> { every { createTempFile(any(), any()) } answers { temp.newFile() } },
            installed,
            importer,
            mockk<IBundledContentSource> { every { readBlocking(any()) } returns ByteArray(0) },
            stamps
        )

        initializer.exec(progress).blockingAwait()

        verify(exactly = 0) { installed.install(any()) }
        verify(exactly = 0) { stamps.markImported(any()) }
        assertIs<ImportException>(strayCauses().single(), "the failure goes to the global handler")
    }

    // InitializeProjects.importProject: blockingSubscribe(onNext)
    @Test
    fun `startup carries on when a project folder fails to import`() {
        val userData = temp.newFolder("user").also { File(it, "project").mkdirs() }
        File(userData, "project/manifest.yaml").writeText("not a manifest")
        every { installed.getInstalledVersion(any()) } returns 2
        val initializer = InitializeProjects(
            mockk(),
            mockk(),
            mockk<IDirectoryProvider> { every { getUserDataDirectory("/") } returns userData },
            installed,
            mockk<IWorkbookRepository> { every { getProjects() } returns Single.just(emptyList()) },
            failingImporters()
        )

        initializer.exec(progress).blockingAwait()

        assertEquals(listOf<Throwable?>(boom), strayCauses(), "the failure goes to the global handler")
    }

    // InitializeProjects.migrateTakesToVersion1 and migrateSourcesToVersion2: Completable.blockingGet() ignored
    @Test
    fun `the projects migration finishes and is recorded when its take and source updates fail`() {
        val takeFile = File(temp.newFolder("book", "c01"), "take.wav").also { it.writeText("audio") }
        val take = Take("take.wav", takeFile, 1, LocalDate.of(2024, 1, 1), null, false, CheckingStatus.UNCHECKED, null, listOf(), 1)
        val sourceFile = temp.newFile("en_ulb.zip")
        val takes = mockk<ITakeRepository> {
            every { getAll() } returns Single.just(listOf(take))
            every { update(any()) } returns Completable.error(boom)
        }
        val sources = mockk<IResourceMetadataRepository> {
            every { getAllSources() } returns Single.just(listOf(metadata(sourceFile)))
            every { update(any<ResourceMetadata>()) } returns Completable.error(boom)
        }
        val initializer = InitializeProjects(
            sources,
            takes,
            mockk<IDirectoryProvider>(relaxed = true) { every { getUserDataDirectory("/") } returns temp.newFolder("user") },
            installed,
            mockk<IWorkbookRepository> { every { getProjects() } returns Single.just(emptyList()) },
            mockk()
        )

        initializer.exec(progress).blockingAwait()

        verify { takes.update(any()) }
        verify { sources.update(any<ResourceMetadata>()) }
        verify { installed.install(initializer) }
        assertFalse(takeFile.exists(), "the take file was moved although its row was not updated")
        assertFalse(sourceFile.exists(), "the source file was deleted although its row was not updated")
        assertTrue(noStrayErrors.drain().isEmpty())
    }

    // InitializeTranslations.insertTranslation: onErrorReturnItem(0)
    @Test
    fun `the translations migration finishes and is recorded when a translation cannot be inserted`() {
        val workbook = mockk<Workbook> {
            every { source.language } returns english
            every { target.language } returns english.copy(slug = "fr", id = 2)
        }
        val initializer = InitializeTranslations(
            installed,
            mockk<IWorkbookRepository> { every { getProjects() } returns Single.just(listOf(workbook)) },
            mockk<ILanguageRepository> { every { insertTranslation(any()) } returns Single.error(boom) }
        )

        initializer.exec(progress).blockingAwait()

        verify { installed.install(initializer) }
        assertTrue(noStrayErrors.drain().isEmpty())
    }

    // ImportLanguages.updateRegions: Completable.blockingGet() ignored
    @Test
    fun `updating language regions reports success when the update fails`() {
        val languages = mockk<ILanguageRepository> { every { updateRegions(any()) } returns Completable.error(boom) }

        ImportLanguages(languages, mockk()).updateRegions("[]".byteInputStream()).blockingAwait()

        verify { languages.updateRegions(emptyList()) }
    }

    // NioFileUtils.copyDirectoryTo: Observable.forEach
    @Test
    fun `a desktop directory copy stops at the first file it cannot write and still lists every file as copied`() {
        val source = temp.newFolder("source")
        listOf("a", "b", "c", "d", "e").forEach { File(source, "$it.txt").writeText(it) }
        val destination = temp.newFolder("destination")
        // A file cannot replace a directory that has something in it.
        listOf("b", "d").forEach { File(destination, "$it.txt/blocker").apply { parentFile.mkdirs() }.writeText("x") }

        val reported = source.toPath().copyDirectoryTo(destination.toPath()) { true }.toList().blockingGet()

        assertEquals(5, reported.size, "all five are reported")
        assertTrue(File(destination, "b.txt").isDirectory && File(destination, "d.txt").isDirectory)
        // Two files cannot be written. One error means it gave up at the first and never tried the second.
        assertIs<IOException>(strayCauses().single())
    }

    // NioFileUtils.copyFileTo: Observable.forEach
    @Test
    fun `a desktop file copy that cannot write still reports the file as copied`() {
        val source = temp.newFile("a.txt")
        val destination = temp.newFolder("destination")
        File(destination, "a.txt/blocker").apply { parentFile.mkdirs() }.writeText("x")

        val reported = source.toPath().copyFileTo(destination.toPath()).toList().blockingGet()

        assertEquals(listOf(File(destination, "a.txt").path), reported)
        assertIs<IOException>(strayCauses().single())
    }

    // PcmTakeTransformer.preTransform and postTransform: Completable.blockingGet() ignored
    @Test
    fun `a PCM take is handed on as converted when either conversion fails`() {
        val pcm = temp.newFile("take.pcm")
        val take = WorkbookTake("take.pcm", pcm, 1, MimeType.WAV, LocalDate.of(2024, 1, 1))
        val converter = mockk<AudioConverter> {
            every { pcmToWav(any(), any()) } returns Completable.error(boom)
            every { wavToPcm(any(), any()) } returns Completable.error(boom)
        }
        val transformer = PcmTakeTransformer(
            mockk<ITempFileProvider> { every { createTempFile(any(), any()) } answers { temp.newFile("converted.wav") } },
            converter
        )

        val wav = transformer.preTransform(take)
        val back = transformer.postTransform(wav)

        assertEquals("converted.wav", wav.file.name)
        assertSame(take, back)
        verify { converter.pcmToWav(pcm, wav.file) }
        verify { converter.wavToPcm(wav.file, pcm) }
    }

    private fun accessorWithFailingWorkbook(): Pair<ProjectFilesAccessor, Workbook> {
        val root = temp.newFolder("home")
        val directories = DesktopDirectoryProvider(
            appName = "OratureDroppedErrorsTest",
            pathSeparator = File.separator,
            userHome = root.absolutePath,
            windowsAppData = root.absolutePath,
            osName = System.getProperty("os.name").uppercase()
        )
        val source = metadata(File(root, "en_ulb"))
        val target = source.copy(language = english.copy(slug = "fr", id = 2), id = 2)
        val accessor = ProjectFilesAccessor(directories, source, target, Collection(1, "gen", "project", "Genesis", target, id = 1))
        val workbook = mockk<Workbook> { every { this@mockk.target.chapters } returns Observable.error(boom) }
        return accessor to workbook
    }

    // ProjectFilesAccessor.writeSelectedTakesFile(workbook, isBook): blockingSubscribe(onNext)
    @Test
    fun `writing the selected takes file empties it and reports success when the takes cannot be read`() {
        val (accessor, workbook) = accessorWithFailingWorkbook()
        val selected = File(accessor.projectDir, RcConstants.SELECTED_TAKES_FILE).also {
            it.parentFile.mkdirs()
            it.writeText("c01/take.wav\n")
        }

        accessor.writeSelectedTakesFile(workbook, isBook = true)

        assertEquals("", selected.readText(), "the previous selection is gone")
        assertEquals(listOf<Throwable?>(boom), strayCauses(), "the failure goes to the global handler")
    }

    // ProjectFilesAccessor.writeSelectedTakesFile(fileWriter, workbook, isBook): blockingSubscribe(onNext)
    @Test
    fun `writing the selected takes into an export writes nothing and reports success when the takes cannot be read`() {
        val (accessor, workbook) = accessorWithFailingWorkbook()
        val written = StringWriter()
        val writer = mockk<IFileWriter> { every { bufferedWriter(any()) } returns BufferedWriter(written) }

        accessor.writeSelectedTakesFile(writer, workbook, isBook = true)

        assertEquals("", written.toString())
        assertEquals(listOf<Throwable?>(boom), strayCauses(), "the failure goes to the global handler")
    }
}
