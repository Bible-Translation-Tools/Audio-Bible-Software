package org.bibletranslationtools.otter.integration

import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.bibletranslationtools.otter.common.domain.resourcecontainer.OtterResourceContainerConfig
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IProjectReader
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.EditionText
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.editionTextOf
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.domain.versification.Versification
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.collections.DeleteProject
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.domain.languages.ImportLanguages
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import io.reactivex.Observable
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.initialization.AuditSourceStructure
import org.bibletranslationtools.otter.common.initialization.RefreshBundledSources
import org.bibletranslationtools.otter.common.domain.project.BundledSourceStamps
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DeleteResourceContainer
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DeleteResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DescribeSourceEditions
import org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceEditionSummary
import org.bibletranslationtools.otter.common.initialization.BackfillEditionFingerprints
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionFingerprintRepository
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionFingerprint
import org.bibletranslationtools.otter.common.persistence.entities.EditionFingerprintEntity
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.initialization.InitializeVersification
import org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceStructureFindings
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.shared.di.koin.appDatabaseModule
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A real database, a real directory tree, and the real Koin graph — the tier this repo did not have.
 *
 * This is the port of the JavaFX app's `integrationtest.projects.DatabaseEnvironment`, which is how
 * Orature verified that importing a resource container produced the right rows. Its absence is why a
 * change to the import path could pass 15 unit tests and still be wrong: `VersificationTreeBuilderTest`
 * pins the tree that gets built and `SourceStructurePlannerTest` pins what fills its gaps, but nothing
 * exercised the join — `importResourceContainer` against an actual database.
 *
 * Differences from the original, all forced by this codebase rather than chosen:
 *
 *  - Koin instead of Dagger. The graph composed here is the PRODUCTION one
 *    ([sharedCommonModules] + [appDatabaseModule]); only the directory provider is swapped, for a
 *    temp root. `jvmAudioModule` is deliberately left out — nothing on the import path needs audio
 *    hardware and a test should not open a device to find that out.
 *  - Languages are loaded from the repo's own `langnames.json` rather than
 *    `ClassLoader.getSystemResourceAsStream("content/langnames.json")`. That lookup is exactly the
 *    bug `IBundledContentSource` exists to prevent: the file is a Compose Multiplatform resource, so
 *    a JVM classpath lookup returns null and the original's `!!` would fail here.
 *
 * Each environment owns a temp directory and a Koin instance, so [close] is not optional. Koin's
 * global state also means these tests cannot run in parallel with each other.
 *
 * ### Running these
 * `./gradlew :shared:integrationTest` — a separate compilation from `desktopTest`, and deliberately
 * not wired into `check`. A ULB import into a real database costs seconds per test, which is worth
 * paying on demand and in CI but not on the loop that runs after every edit.
 */
class IntegrationEnvironment private constructor(
    private val tempRoot: File,
    private val koin: Koin
) : AutoCloseable {

    val db: DaoProvider = koin.get()
    val directoryProvider: IDirectoryProvider = koin.get()

    private val importerFactory: RCImporterFactory = koin.get()

    /** A fresh importer chain (ongoing → existing source → new source), as the app builds it. */
    val importer get() = importerFactory.makeImporter()

    // ── actions ──────────────────────────────────────────────────────────────────────────

    /**
     * Imports a resource container from `desktopIntegrationTest/resources/resource-containers/`, asserting it
     * succeeded. Returns `this` so a test can chain imports the way the original did.
     */
    fun import(rcFile: String): IntegrationEnvironment = import(rcResourceFile(rcFile))

    /** Imports an arbitrary resource container file — see [withBookTruncated]. */
    fun import(rc: File): IntegrationEnvironment {
        val result = importer.import(rc).blockingGet()
        assertEquals(ImportResult.SUCCESS, result, "importing ${rc.name}")
        return this
    }

    /**
     * Derives a copy of [rcFile] in this environment's temp directory whose [usfmEntry] stops after
     * [keepVerses] verses, and returns it.
     *
     * This is what makes the versification pre-allocation observable. For a source whose text covers
     * its versification completely — the committed ULB — pre-allocating and not pre-allocating
     * produce byte-identical databases, so no assertion over that fixture can tell them apart.
     * Truncating one book creates the gap the feature exists to fill: the versification still declares
     * the whole chapter, the text no longer supplies it.
     *
     * Derived at test time rather than committed as a second fixture, so there is one binary in the
     * repo and the difference between the two inputs is stated in code rather than hidden in a zip.
     */
    fun withBookTruncated(rcFile: String, usfmEntry: String, keepVerses: Int): File =
        withUsfmEdited(rcFile, usfmEntry, "truncated-$keepVerses") { usfm ->
            // Cut at the first verse marker beyond the keep count. USFM is read forward, so dropping
            // the tail simply means those verses are not in the text.
            val cutAt = usfm.indexOf("\\v ${keepVerses + 1} ")
            assertTrue(cutAt > 0, "no verse ${keepVerses + 1} marker in '$usfmEntry' to truncate at")
            usfm.substring(0, cutAt)
        }

    /**
     * [rcFile] with one verse, marker and text, removed from a single-chapter book: the gap an
     * omitted textual variant leaves inside a chapter.
     */
    fun withVerseRemoved(rcFile: String, usfmEntry: String, verse: Int): File =
        withUsfmEdited(rcFile, usfmEntry, "without-v$verse") { usfm ->
            val from = usfm.indexOf("\\v $verse ")
            val to = usfm.indexOf("\\v ${verse + 1} ")
            assertTrue(from > 0 && to > from, "no verse $verse to remove in '$usfmEntry'")
            usfm.removeRange(from, to)
        }

    private fun withUsfmEdited(rcFile: String, usfmEntry: String, label: String, edit: (String) -> String): File =
        withEntriesEdited(rcFile, "${usfmEntry.substringBefore('.')}-$label", mapOf(usfmEntry to edit))

    /**
     * A strictly newer edition of [rcFile]: issued and modified on 2024-07-12, and with Jude 1:1
     * reworded, so its fingerprint differs too.
     */
    fun newerEdition(rcFile: String): File =
        withEntriesEdited(
            rcFile, "newer-edition",
            mapOf(
                "manifest.yaml" to { manifest ->
                    manifest.replace("issued: '2017-11-29'", "issued: '2024-07-12'")
                        .replace("modified: '2017-11-29'", "modified: '2024-07-12'")
                },
                "66-JUD.usfm" to { usfm -> usfm.replaceFirst("\\v 1 ", "\\v 1 (revised) ") }
            )
        )

    private fun withEntriesEdited(rcFile: String, label: String, edits: Map<String, (String) -> String>): File {
        val source = rcResourceFile(rcFile)
        val target = File(tempRoot, "$label.zip")

        ZipFile(source).use { zip ->
            edits.keys.forEach { assertNotNull(zip.getEntry(it), "'$it' is not in $rcFile") }
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                zip.entries().asSequence().forEach { source ->
                    if (source.isDirectory) return@forEach
                    out.putNextEntry(ZipEntry(source.name))
                    val edit = edits[source.name]
                    if (edit != null) {
                        val edited = edit(zip.getInputStream(source).bufferedReader().readText())
                        out.write(edited.toByteArray())
                    } else {
                        zip.getInputStream(source).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
        }
        return target
    }

    /** A source the app bundles, read from the repo rather than the test classpath. */
    fun bundledSource(fileName: String): File {
        val file = File(repoRoot(), "shared/src/commonMain/composeResources/files/content/$fileName")
        assertTrue(file.isFile, "bundled source not found at ${file.absolutePath}")
        return file
    }

    /** A versification as source import reads it, or null if it isn't installed. */
    fun versification(code: String): Versification? =
        koin.get<IVersificationRepository>().getVersification(code).blockingGet()

    /** The single installed source's stored fingerprint, or null if it has none. */
    fun storedFingerprint(): EditionFingerprint? = runBlocking {
        koin.get<IEditionFingerprintRepository>().get(installedSource().id)
    }

    /** Removes the installed source's fingerprint, as a database from before schema v15 has none. */
    fun clearFingerprint() {
        val id = installedSource().id
        db.resourceMetadataDao.setEditionFingerprint(id, EditionFingerprintEntity(null, null, null))
        db.editionChapterDao.replaceForEdition(id, emptyList())
    }

    /** Runs the startup fingerprint backfill. */
    fun backfillFingerprints() {
        Observable.create<ProgressStatus> { emitter ->
            koin.get<BackfillEditionFingerprints>().exec(emitter).blockingAwait()
            emitter.onComplete()
        }.blockingSubscribe()
    }

    private fun installedSource() = db.resourceMetadataDao.fetchAll().single { it.derivedFromFk == null }

    /** The committed fixture [rcFile], for tests that need the file itself. */
    fun fixture(rcFile: String): File = rcResourceFile(rcFile)

    /** Every installed source edition (not derived rows), in install order. */
    fun sourceEditions() = db.resourceMetadataDao.fetchAll().filter { it.derivedFromFk == null }.sortedBy { it.id }

    /** Whether the edition in [rc] is already installed, as the app asks before bundling one. */
    fun isAlreadyImported(rc: File): Boolean = koin.get<ImportProjectUseCase>().isAlreadyImported(rc)

    /** Deletes the source edition stored at [path], as the app would. */
    fun deleteSource(path: String): DeleteResult =
        koin.get<DeleteResourceContainer>().deleteSync(File(path))

    /** Runs the startup step that re-imports bundled sources whose zip changed. */
    fun refreshBundledSources() {
        Observable.create<ProgressStatus> { emitter ->
            koin.get<RefreshBundledSources>().exec(emitter).blockingAwait()
            emitter.onComplete()
        }.blockingSubscribe()
    }

    /** Whether bundled source [name]'s current zip is recorded as imported. */
    fun bundledSourceIsCurrent(name: String): Boolean = koin.get<BundledSourceStamps>().isCurrent(name)

    /** The source edition a derived row (a project's target container) was derived from. */
    fun derivedFromOf(derivedId: Int): Int? = db.resourceMetadataDao.fetchById(derivedId)?.derivedFromFk

    /** Where [project]'s files live. */
    fun projectDirectory(project: Collection): File {
        val target = project.resourceContainer!!
        val sourceId = derivedFromOf(target.id)!!
        val source = koin.get<IResourceMetadataRepository>().getAllSources().blockingGet().single { it.id == sourceId }
        return directoryProvider.getProjectDirectory(source, target, project.slug)
    }

    /** How the app would describe the installed source edition [sourceId]. */
    fun describeEdition(sourceId: Int): SourceEditionSummary = runBlocking {
        val edition = koin.get<IResourceMetadataRepository>().getAllSourcesSuspend().single { it.id == sourceId }
        koin.get<DescribeSourceEditions>().describe(edition)
    }

    /** The verse text of the source in [rc], parsed as import would parse it. */
    fun editionText(rc: File): EditionText =
        ResourceContainer.load(rc, OtterResourceContainerConfig()).use { container ->
            editionTextOf(IProjectReader.constructContainerTree(container, koin.get<IZipEntryTreeBuilder>()))
        }

    /** Deletes every project, as the app's project management does. */
    fun deleteAllProjects() {
        val descriptors = koin.get<IWorkbookDescriptorRepository>().getAll(computeSourceAudio = false).blockingGet()
        koin.get<DeleteProject>().deleteProjects(descriptors).blockingAwait()
    }

    /** The internal directory source editions live under. */
    val sourceRoot: File get() = directoryProvider.internalSourceRCDirectory

    /** What the one-time source structure report would say about every installed source. */
    fun auditSources(): List<SourceStructureFindings> = koin.get<AuditSourceStructure>().audit()

    /**
     * @param deriveProjectFromVerses whether verse rows are derived into the target. NOT inferred from
     *   [mode] — `CreateProject.create` takes the two independently, and only `createAllBooks` couples
     *   them (`isVerseByVerse = projectMode != TRANSLATION`). The recorder passes both explicitly.
     */
    fun createProject(
        sourceProject: Collection,
        targetLanguage: Language,
        mode: ProjectMode? = null,
        deriveProjectFromVerses: Boolean = false
    ): Collection = koin.get<CreateProject>()
        .create(sourceProject, targetLanguage, mode, resourceId = null, deriveProjectFromVerses)
        .blockingGet()

    /** An imported source book by slug, e.g. "jud"; of edition [sourceId] when several are installed. */
    fun sourceBook(slug: String, sourceId: Int? = null): Collection {
        val projects = koin.get<ICollectionRepository>().getSourceProjects().blockingGet()
        return projects.firstOrNull { it.slug == slug && (sourceId == null || it.resourceContainer?.id == sourceId) }
            ?: error("no source project '$slug'; imported: ${projects.map { it.slug }.sorted().take(10)}…")
    }

    fun language(slug: String): Language = koin.get<ILanguageRepository>().getBySlug(slug).blockingGet()

    fun derivedProjects(): List<Collection> =
        koin.get<ICollectionRepository>().getDerivedProjects().blockingGet()

    fun childrenOf(collection: Collection): List<Collection> =
        koin.get<ICollectionRepository>().getChildren(collection).blockingGet()

    /**
     * Content rows for a collection, and for each one the source content it derives from.
     *
     * The `content_derivative` links are what make a target project a *translation of* something
     * rather than a set of unrelated rows — chapter compilation and the source-text panels both walk
     * back through them.
     */
    fun contentWithSources(collection: Collection): Map<ContentEntity, List<ContentEntity>> =
        db.contentDao.fetchByCollectionId(collection.id).associateWith { db.contentDao.fetchSources(it) }

    // ── assertions ───────────────────────────────────────────────────────────────────────

    /**
     * Compares only the counts [expected] actually specifies, so a test can pin content rows without
     * committing to a link count it does not care about.
     */
    fun assertRowCounts(expected: RowCount, message: String? = null): IntegrationEnvironment {
        val actual = RowCount(
            contents = expected.contents?.let { contentRowCounts() },
            collections = expected.collections?.let { db.collectionDao.fetchAll().count() },
            links = expected.links?.let { db.resourceLinkDao.fetchAll().count() }
        )
        assertEquals(expected, actual, message)
        return this
    }

    /**
     * Asserts each chapter's verse count, ignoring content with no text.
     *
     * Ignoring null text is not laziness, it is the versification pre-allocation: a source import
     * allocates a row for every verse the versification declares, and the source text will not cover
     * all of them. The original carried the same filter with the same reason —
     * "remove content allocated from versification without a matching verse in ULB".
     */
    fun assertChapters(rcSlug: String, vararg chapter: ChapterVerse): IntegrationEnvironment {
        val rc = db.resourceMetadataDao.fetchAll().firstOrNull { it.identifier == rcSlug }
        assertNotNull(rc, "no resource container with identifier '$rcSlug' was imported")

        val chapters = db.collectionDao.fetchAll().filter { it.dublinCoreFk == rc.id }
        chapter.forEach { (slug, verseCount) ->
            // Looked up by slug alone, deliberately. The label is NOT a reliable key here: the
            // versification builder labels a chapter with ChapterLabel.of (so "psalm" for Psalms)
            // while UsfmProjectReader hardcodes ContentLabel.CHAPTER, so which label a row carries
            // depends on which tree created it.
            val entity: CollectionEntity? = chapters.firstOrNull { it.slug == slug }
            assertNotNull(
                entity,
                "no chapter collection '$slug' in '$rcSlug' — slugs present for this container: " +
                    chapters.map { it.slug }.sorted().take(12).joinToString() + "…"
            )

            val content = db.contentDao.fetchByCollectionId(entity.id)
            val textType = db.contentTypeDao.fetchId(ContentType.TEXT)
            val metaType = db.contentTypeDao.fetchId(ContentType.META)

            assertEquals(
                verseCount,
                content.count { it.type_fk == textType && it.text != null },
                "verses with text in $slug"
            )
            assertEquals(1, content.count { it.type_fk == metaType }, "meta chunks in $slug")
        }
        return this
    }

    /**
     * TEXT rows with no text: verses the versification declares that the source does not cover.
     *
     * Deliberately restricted to TEXT. Filtering all content on `text == null` matches every META
     * chunk as well — those are created with null text by both tree builders — so the unrestricted
     * version is non-empty regardless of whether pre-allocation ran at all.
     */
    fun uncoveredVerseRows(): List<ContentEntity> {
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)
        return db.contentDao.fetchAll().filter { it.type_fk == textType && it.text == null }
    }

    /**
     * Verse rows for one chapter, split by whether they carry text.
     *
     * [total] is what the versification allocated and [withText] what the source supplied, so the
     * difference is the pre-allocation. Both are the point: a total on its own cannot distinguish
     * "pre-allocated 25" from "parsed 25 out of the text".
     */
    fun verseCounts(chapterSlug: String, sourceId: Int? = null): VerseCounts {
        val chapter = db.collectionDao.fetchAll()
            .firstOrNull { it.slug == chapterSlug && (sourceId == null || it.dublinCoreFk == sourceId) }
        assertNotNull(chapter, "no chapter collection '$chapterSlug'")
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)
        val verses = db.contentDao.fetchByCollectionId(chapter.id).filter { it.type_fk == textType }
        return VerseCounts(total = verses.size, withText = verses.count { it.text != null })
    }

    private fun contentRowCounts(): Map<ContentType, Int> =
        db.contentDao.fetchAll()
            .groupBy { it.type_fk }
            .mapValues { it.value.count() }
            .mapKeys { db.contentTypeDao.fetchForId(it.key)!! }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────

    override fun close() {
        stopKoin()
        tempRoot.deleteRecursively()
    }

    companion object {
        /**
         * Builds an environment over a fresh temp directory. The database bootstraps itself:
         * SQLDelight creates the schema from the `.sq` files when the file does not exist, then
         * applies migrations.
         */
        fun create(): IntegrationEnvironment {
            val tempRoot = File.createTempFile("orature-integration", "").let {
                it.delete()
                it.mkdirs()
                it
            }
            val provider = DesktopDirectoryProvider(
                appName = "OratureIntegrationTest",
                pathSeparator = File.separator,
                userHome = tempRoot.absolutePath,
                windowsAppData = tempRoot.absolutePath,
                osName = System.getProperty("os.name").uppercase()
            )
            val koin = startKoin {
                modules(
                    sharedCommonModules + appDatabaseModule + module {
                        single<IDirectoryProvider> { provider }
                    }
                )
            }.koin

            return IntegrationEnvironment(tempRoot, koin).apply {
                initializeVersification()
                importLanguages()
            }
        }

        /**
         * Versification rows are a precondition for the source importer's pre-allocation, and their
         * absence is silent.
         *
         * `NewSourceImporter` wraps the tree build in a `runCatching` so a missing versification
         * degrades to a text-only import instead of failing it. That is right for the app and a trap
         * for a test: without this call `getVersification` throws (the DAO has no row, so the file
         * name is null), the importer swallows it, and the import quietly takes the text-only path —
         * which for a complete ULB produces byte-identical row counts. The pre-allocation assertions
         * then pass whether or not pre-allocation runs at all.
         *
         * The app does this via `InitializeApp`, which runs this initializer first.
         */
        private fun IntegrationEnvironment.initializeVersification() {
            Observable.create<ProgressStatus> { emitter ->
                koin.get<InitializeVersification>().exec(emitter).blockingAwait()
                emitter.onComplete()
            }.blockingSubscribe()
        }

        /**
         * Language rows are a precondition for importing anything: an RC names its language by slug
         * and the import resolves it against the language table.
         */
        private fun IntegrationEnvironment.importLanguages() {
            langNamesFile().inputStream().use { stream ->
                koin.get<ImportLanguages>().import(stream).blockingAwait()
            }
        }

        /** The repo's own bundled catalogue, read from source rather than the classpath. */
        private fun langNamesFile(): File {
            val file = File(repoRoot(), "shared/src/commonMain/composeResources/files/content/langnames.json")
            assertTrue(file.isFile, "langnames.json not found at ${file.absolutePath}")
            return file
        }

        private fun rcResourceFile(rcFile: String): File {
            val url = IntegrationEnvironment::class.java.classLoader
                .getResource("resource-containers/$rcFile")
            assertNotNull(
                url,
                "fixture 'resource-containers/$rcFile' is not on the test classpath — it belongs in " +
                    "shared/src/desktopIntegrationTest/resources/resource-containers/"
            )
            return File(url.toURI())
        }

        /** Gradle runs tests with the working directory set to the project dir (shared/). */
        private fun repoRoot(): File {
            var dir = File(".").absoluteFile
            while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile
            }
            return dir
        }
    }
}

/**
 * Expected row counts. A null field is not compared, so a test pins only what it means to pin.
 */
data class RowCount(
    val collections: Int? = null,
    val links: Int? = null,
    val contents: Map<ContentType, Int>? = null
)

/** A chapter slug and the number of verses that should carry text. */
data class ChapterVerse(val chapter: String, val verses: Int)

/** Verse rows in a chapter: how many exist, and how many the source text filled in. */
data class VerseCounts(val total: Int, val withText: Int)
