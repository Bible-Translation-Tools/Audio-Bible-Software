package org.bibletranslationtools.otter.integration

import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.exceptions.OnErrorNotImplementedException
import org.bibletranslationtools.otter.common.NoStrayErrorsRule
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceContainerRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.domain.project.GlSourceCatalog
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.persistence.repositories.ContentRepository
import org.bibletranslationtools.otter.common.persistence.repositories.LanguageRepository
import org.bibletranslationtools.otter.common.persistence.repositories.ResourceContainerRepository
import org.bibletranslationtools.otter.common.persistence.repositories.TakeRepository
import org.junit.Rule
import org.koin.dsl.module
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The places inside the importers where an error is dropped today, each made to fail and its
 * outcome pinned. The rest of the list, and why it is pinned, is in `DroppedErrorsTest`.
 *
 * Each test imports for real, with one repository call replaced by a failure.
 */
class DroppedImportErrorsTest {

    @get:Rule
    val noStrayErrors = NoStrayErrorsRule()

    private val boom = IOException("injected")
    private val environments = mutableListOf<IntegrationEnvironment>()
    private val kept = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        environments.forEach { it.close() }
        kept.forEach { it.delete() }
    }

    /** The production graph, with the repositories the importers write through wrapped in spies. */
    private val spies = module {
        single<ILanguageRepository> { spyk(get<LanguageRepository>()) }
        single<ITakeRepository> { spyk(get<TakeRepository>()) }
        single<IContentRepository> { spyk(get<ContentRepository>()) }
        single<IResourceContainerRepository> { spyk(get<ResourceContainerRepository>()) }
    }

    private fun environment(spied: Boolean = false) =
        (if (spied) IntegrationEnvironment.create(spies) else IntegrationEnvironment.create()).also { environments += it }

    /** A backup of a French Jude project, made on an install of its own and kept after that install is gone. */
    private fun judeBackup(mode: ProjectMode, withTake: Boolean = true): File {
        val environment = environment().apply { import("en_ulb.zip") }
        val project = environment.createProject(
            environment.sourceBook("jud", environment.sourceEditions().single().id), environment.language("fr"),
            mode = mode, deriveProjectFromVerses = mode == ProjectMode.NARRATION
        )
        if (withTake) environment.recordTake(project, sort = 1, verse = 3)
        val backup = environment.backup(project)
            .copyTo(File.createTempFile("dropped-errors-backup", ".zip"), overwrite = true).also { kept += it }
        environment.close()
        environments -= environment
        return backup
    }

    /** An install with the ULB, ready to import a backup into, whose repositories can be made to fail. */
    private fun install() = environment(spied = true).apply { import("en_ulb.zip") }

    private fun strayCauses(): List<Throwable?> = noStrayErrors.drain().map {
        assertIs<OnErrorNotImplementedException>(it)
        it.cause
    }

    // OngoingProjectImporter: languageRepository.updateTranslation(translation).subscribe()
    @Test
    fun `a project import succeeds when the translation timestamp cannot be written`() {
        val backup = judeBackup(ProjectMode.NARRATION)
        val environment = install()
        every { environment.koinGet<ILanguageRepository>().updateTranslation(any()) } returns Completable.error(boom)

        assertEquals(ImportResult.SUCCESS, environment.importer.import(backup).blockingGet())

        assertEquals(listOf<Throwable?>(boom), strayCauses(), "the failure goes to the global handler")
    }

    // OngoingProjectImporter.createTranslation: onErrorReturnItem(0)
    @Test
    fun `a project import succeeds when the importer's own translation insert fails`() {
        val backup = judeBackup(ProjectMode.NARRATION)
        val environment = install()
        // Creating the project inserts the translation first, and a failure there fails the import.
        // The importer then inserts it again; only that second failure is swallowed.
        every { environment.koinGet<ILanguageRepository>().insertTranslation(any()) } answers
            { callOriginal() } andThen Single.error(boom)

        assertEquals(ImportResult.SUCCESS, environment.importer.import(backup).blockingGet())

        assertEquals(1, environment.db.translationDao.fetchAll().size)
        assertTrue(noStrayErrors.drain().isEmpty())
    }

    @Test
    fun `a project import fails when creating the project cannot insert the translation`() {
        val backup = judeBackup(ProjectMode.NARRATION)
        val environment = install()
        every { environment.koinGet<ILanguageRepository>().insertTranslation(any()) } returns Single.error(boom)

        assertEquals(ImportResult.FAILED, environment.importer.import(backup).blockingGet())
    }

    // OngoingProjectImporter.deriveChapterContentFromVerses: Completable.blockingGet() ignored
    @Test
    fun `an import of an Orature 1 project succeeds when a chapter's old takes cannot be deleted`() {
        val full = judeBackup(ProjectMode.NARRATION)
        val environment = install()
        // A backup with no project mode file is read as an Orature 1 project.
        val backup = environment.withoutEntries(full, ".apps/orature/project_mode.json")
        every { environment.koinGet<ITakeRepository>().deleteForContent(any()) } returns Completable.error(boom)

        assertEquals(ImportResult.SUCCESS, environment.importer.import(backup).blockingGet())

        verify { environment.koinGet<ITakeRepository>().deleteForContent(any()) }
        assertTrue(noStrayErrors.drain().isEmpty())
    }

    // OngoingProjectImporter.resetChaptersWithoutTakes: Completable.blockingGet() ignored
    @Test
    fun `a translation project import succeeds when an untouched chapter cannot be reset`() {
        val backup = judeBackup(ProjectMode.TRANSLATION, withTake = false)
        val environment = install()
        every { environment.koinGet<IContentRepository>().deleteForCollection(any(), null) } returns Completable.error(boom)

        assertEquals(ImportResult.SUCCESS, environment.importer.import(backup).blockingGet())

        verify { environment.koinGet<IContentRepository>().deleteForCollection(any(), null) }
        assertTrue(noStrayErrors.drain().isEmpty())
    }

    // NewSourceImporter.importContainer: nested subscribe(onSuccess) inside Single.create
    @Test
    fun `a source import never finishes if storing the container throws instead of returning a result`() {
        val environment = environment(spied = true)
        every {
            environment.koinGet<IResourceContainerRepository>().importResourceContainer(any(), any(), any(), any())
        } returns Single.error(boom)

        // Today nothing throws there: the repository turns every failure into a result. This is what
        // that guard protects.
        val failure = assertFailsWith<RuntimeException> {
            environment.importer.import(environment.fixture("en_ulb.zip")).timeout(3, TimeUnit.SECONDS).blockingGet()
        }

        assertIs<TimeoutException>(failure.cause)
        assertEquals(listOf<Throwable?>(boom), strayCauses(), "the failure goes to the global handler")
    }

    // ImportProjectUseCase.sideloadSource: ignoreElement() on the import result
    @Test
    fun `sideloading a bundled source completes when the import fails`() {
        val environment = environment(spied = true)
        every {
            environment.koinGet<IResourceContainerRepository>().importResourceContainer(any(), any(), any(), any())
        } returns Single.just(ImportResult.IMPORT_ERROR)
        val catalog = environment.koinGet<GlSourceCatalog>()
        val bundled = catalog.sources.first { it.name in catalog.embeddedSourceNames }

        environment.koinGet<ImportProjectUseCase>().sideloadSource(environment.language(bundled.languageCode)).blockingAwait()

        assertTrue(environment.sourceEditions().isEmpty(), "nothing was installed")
        assertTrue(!environment.bundledSourceIsCurrent(bundled.name), "and it is not marked as imported")
    }
}
