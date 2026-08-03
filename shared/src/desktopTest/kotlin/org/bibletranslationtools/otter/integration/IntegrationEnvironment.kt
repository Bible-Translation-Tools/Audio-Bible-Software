package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.languages.ImportLanguages
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import io.reactivex.Observable
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.VersificationTreeBuilder
import org.bibletranslationtools.otter.common.initialization.InitializeVersification
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.shared.di.koin.appDatabaseModule
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A real database, a real directory tree, and the real Koin graph — the tier this repo did not have.
 *
 * This is the port of the JavaFX app's `integrationtest.projects.DatabaseEnvironment`, which is how
 * Orature verified that importing a resource container produced the right rows. Its absence is why a
 * change to the import path could pass 15 unit tests and still be wrong: `VersificationTreeBuilderTest`
 * pins the tree that gets built and `PlanImportTest` pins which tree is chosen, but nothing exercised
 * the join — `importResourceContainer` and `updateContent` against an actual database.
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
 */
class IntegrationEnvironment private constructor(
    private val tempRoot: File,
    private val koin: Koin
) : AutoCloseable {

    val db: IAppDatabase = koin.get()
    val directoryProvider: IDirectoryProvider = koin.get()

    private val importerFactory: RCImporterFactory = koin.get()

    /** A fresh importer chain (ongoing → existing source → new source), as the app builds it. */
    val importer get() = importerFactory.makeImporter()

    // ── actions ──────────────────────────────────────────────────────────────────────────

    /**
     * Imports a resource container from `desktopTest/resources/resource-containers/`, asserting it
     * succeeded. Returns `this` so a test can chain imports the way the original did.
     */
    fun import(rcFile: String): IntegrationEnvironment {
        val result = importer.import(rcResourceFile(rcFile)).blockingGet()
        assertEquals(ImportResult.SUCCESS, result, "importing $rcFile")
        return this
    }

    fun createProject(sourceProject: Collection, targetLanguage: Language): Collection =
        koin.get<CreateProject>().create(sourceProject, targetLanguage).blockingGet()

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
     * The versification trees the source importer would pre-allocate from, for [rcFile].
     *
     * Exposed so a test can assert the path is REACHABLE. `NewSourceImporter` degrades to a text-only
     * import when the versification cannot be read, and does it silently by design — so without this,
     * a broken versification looks exactly like a working one for any source whose text is complete.
     */
    fun versificationTreesFor(rcFile: String): List<*>? =
        VersificationTreeBuilder(koin.get()).build(ResourceContainer.load(rcResourceFile(rcFile)))

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
         * `AppDatabase` runs `sql/CreateAppDb.sql` (shipped in the otter-db artifact) when the file
         * does not exist, then applies migrations.
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
                    "shared/src/desktopTest/resources/resource-containers/"
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
