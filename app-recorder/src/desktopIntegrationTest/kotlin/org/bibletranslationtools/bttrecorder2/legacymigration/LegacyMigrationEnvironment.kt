package org.bibletranslationtools.bttrecorder2.legacymigration

import io.reactivex.Observable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.languages.ImportLanguages
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.importer.NewSourceImporter
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.initialization.InitializeVersification
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.shared.di.koin.appDatabaseModule
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertTrue

/**
 * A real database, directory tree and Koin graph for testing the legacy migration end to end, the
 * recorder's counterpart of `:shared`'s `IntegrationEnvironment`.
 *
 * The graph is the production one ([sharedCommonModules] + [appDatabaseModule]); only the directory
 * provider is swapped for a temp root. Koin is global, so tests using this can't run in parallel,
 * and [close] is not optional.
 */
class LegacyMigrationEnvironment private constructor(
    private val tempRoot: File,
    val koin: Koin
) : AutoCloseable {

    val db: DaoProvider = koin.get()

    // ── sources ──────────────────────────────────────────────────────────────────────────────

    /** Installs [rc] as a new source edition, the way the migration installs its templates. */
    fun installAsNewSource(rc: File): ImportResult =
        koin.get<NewSourceImporter>().import(rc).blockingGet()

    /** Imports [rc] through the full importer chain, the way the app installs a bundled source. */
    fun importThroughChain(rc: File): ImportResult =
        koin.get<RCImporterFactory>().makeImporter().import(rc).blockingGet()

    /** Every installed source edition (not a project's derived row), in install order. */
    fun sourceEditions(): List<ResourceMetadataEntity> =
        db.resourceMetadataDao.fetchAll().filter { it.derivedFromFk == null }.sortedBy { it.id }

    /** The installed source edition titled [title]. */
    fun edition(title: String): ResourceMetadataEntity =
        sourceEditions().singleOrNull { it.title == title }
            ?: error("no single edition titled '$title'; installed: ${sourceEditions().map { it.title }}")

    /**
     * Everything the database holds for source edition [editionId]: its metadata and, for every
     * collection under it, each content row's place and text. Two equal snapshots mean nothing
     * about the edition changed.
     */
    fun snapshot(editionId: Int): EditionSnapshot {
        val collections = db.collectionDao.fetchAll().filter { it.dublinCoreFk == editionId }
        val rows = collections.flatMap { collection ->
            db.contentDao.fetchByCollectionId(collection.id).map { row ->
                listOf(collection.slug, row.sort, row.start, row.end, row.bridged, row.text).joinToString("|")
            }
        }.sorted()
        return EditionSnapshot(db.resourceMetadataDao.fetchById(editionId), collections.size, rows)
    }

    /** A source book by slug, from edition [editionId]. */
    fun sourceBook(slug: String, editionId: Int): Collection =
        koin.get<ICollectionRepository>().getSourceProjects().blockingGet()
            .singleOrNull { it.slug == slug && it.resourceContainer?.id == editionId }
            ?: error("no source book '$slug' in edition $editionId")

    // ── templates ────────────────────────────────────────────────────────────────────────────

    /** Reads the templates from the repo's assets folder, as the app reads them from its own assets. */
    val templatesFromDisk: IUlbTemplateFiles = object : IUlbTemplateFiles {
        override fun open(template: UlbTemplate): InputStream = templateFile(template).inputStream()
    }

    /** The install step, built from the production graph, reading the templates from [files]. */
    fun installUlbTemplates(files: IUlbTemplateFiles = templatesFromDisk): InstallUlbTemplates =
        InstallUlbTemplates(files, koin.get(), koin.get(), koin.get())

    /**
     * A copy of [template] with the same manifest (title, version, dates) but different wording in
     * Jude 1:1: a different edition that looks the same by its labels, as an older bundled ULB does
     * next to the verse template.
     */
    fun lookAlikeOf(template: UlbTemplate): File {
        val target = File(tempRoot, "look-alike-${template.name.lowercase()}.zip")
        ZipFile(templateFile(template)).use { zip ->
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                zip.entries().asSequence().forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        if (entry.name.endsWith("66-JUD.usfm")) {
                            val text = bytes.decodeToString()
                            assertTrue("\\v 1 " in text, "no Jude 1:1 to reword in $template")
                            out.write(text.replaceFirst("\\v 1 ", "\\v 1 (reworded) ").encodeToByteArray())
                        } else {
                            out.write(bytes)
                        }
                    }
                    out.closeEntry()
                }
            }
        }
        return target
    }

    // ── projects ─────────────────────────────────────────────────────────────────────────────

    fun language(slug: String): Language = koin.get<ILanguageRepository>().getBySlug(slug).blockingGet()

    /** A dialect project of [sourceBook] in [targetLanguage], with a row per source verse. */
    fun createDialectProject(sourceBook: Collection, targetLanguage: Language): Collection =
        koin.get<CreateProject>()
            .create(sourceBook, targetLanguage, ProjectMode.DIALECT, resourceId = null, deriveProjectFromVerses = true)
            .blockingGet()

    /** The verse ranges a project chapter records, in order. */
    fun projectVerses(project: Collection, chapter: Int): List<IntRange> {
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)
        val chapterRow = db.collectionDao.fetchChildren(db.collectionDao.fetchById(project.id))
            .single { it.sort == chapter }
        return db.contentDao.fetchByCollectionId(chapterRow.id)
            .filter { it.type_fk == textType && it.labelKey == "verse" && !it.bridged }
            .sortedBy { it.start }
            .map { it.start..it.end }
    }

    // ── backups ──────────────────────────────────────────────────────────────────────────────

    /** The backup step, built from the production graph, reading the templates from [files]. */
    fun writeMigrationBackup(files: IUlbTemplateFiles = templatesFromDisk): WriteMigrationBackup =
        WriteMigrationBackup(CompileLegacyChapterTake(koin.get(), koin.get()), koin.get(), files, koin.get())

    /** Imports [backup] the way the app restores a backup. */
    fun importBackup(backup: File): ImportResult =
        koin.get<ImportProjectUseCase>().import(backup).blockingGet()

    /** A path under this environment's temp root, for files a test writes. */
    fun scratch(path: String): File = File(tempRoot, path).also { it.parentFile?.mkdirs() }

    /** Project book [book] in [targetLanguage] as the database has it, with each chapter's chapter takes. */
    fun importedProject(book: String, targetLanguage: String): ImportedProject {
        val languageId = db.languageDao.fetchBySlug(targetLanguage)?.id ?: error("no language '$targetLanguage'")
        val derived = db.resourceMetadataDao.fetchAll()
            .filter { it.derivedFromFk != null && it.languageFk == languageId }
            .map { it.id }
            .toSet()
        val row = db.collectionDao.fetchByLabel("project").singleOrNull { it.slug == book && it.dublinCoreFk in derived }
            ?: error("no single project book '$book' in '$targetLanguage'")
        val metaType = db.contentTypeDao.fetchId(ContentType.META)
        val chapters = db.collectionDao.fetchChildren(row).associate { chapter ->
            val takes = db.contentDao.fetchByCollectionId(chapter.id)
                .filter { it.type_fk == metaType }
                .flatMap { content ->
                    db.takeDao.fetchByContentId(content.id).map { ImportedTake(File(it.filepath), it.number, content.selectedTakeFk == it.id) }
                }
                .sortedBy { it.number }
            chapter.sort to takes
        }.filterValues { it.isNotEmpty() }
        val mode = db.workbookDescriptorDao.fetchAll().singleOrNull { it.targetFk == row.id }
            ?.let { db.workbookTypeDao.fetchById(it.typeFk) }
        return ImportedProject(row, db.collectionDao.fetchSource(row)?.dublinCoreFk, mode, chapters)
    }

    override fun close() {
        stopKoin()
        tempRoot.deleteRecursively()
    }

    data class EditionSnapshot(
        val metadata: ResourceMetadataEntity?,
        val collectionCount: Int,
        val rows: List<String>
    )

    /**
     * A project book: the edition its source book is in, its mode, and the chapter takes of each
     * chapter that has any, by take number.
     */
    data class ImportedProject(
        val book: CollectionEntity,
        val editionId: Int?,
        val mode: ProjectMode?,
        val chapterTakes: Map<Int, List<ImportedTake>>
    )

    data class ImportedTake(val file: File, val number: Int, val selected: Boolean)

    companion object {

        fun create(): LegacyMigrationEnvironment {
            val tempRoot = File.createTempFile("recorder-legacy-migration", "").apply { delete(); mkdirs() }
            val provider = DesktopDirectoryProvider(
                appName = "RecorderLegacyMigrationTest",
                pathSeparator = File.separator,
                userHome = tempRoot.absolutePath,
                windowsAppData = tempRoot.absolutePath,
                osName = System.getProperty("os.name").uppercase()
            )
            val koin = startKoin {
                modules(sharedCommonModules + appDatabaseModule + module { single<IDirectoryProvider> { provider } })
            }.koin
            return LegacyMigrationEnvironment(tempRoot, koin).apply {
                // Source import pre-allocates verse rows from these; without them it silently falls
                // back to text-only import.
                Observable.create<ProgressStatus> { emitter ->
                    koin.get<InitializeVersification>().exec(emitter).blockingAwait()
                    emitter.onComplete()
                }.blockingSubscribe()
                // Every container names its language by slug, resolved against this table.
                repoFile("shared/src/commonMain/composeResources/files/content/langnames.json")
                    .inputStream().use { koin.get<ImportLanguages>().import(it).blockingAwait() }
            }
        }

        /** A shipped template, read from the Android assets folder. */
        fun templateFile(template: UlbTemplate): File =
            repoFile("app-recorder/src/androidMain/assets/${template.assetPath}")

        /** The English ULB the app bundles, downloaded by `:shared:downloadGLSources`. */
        fun bundledUlb(): File = repoFile(
            "shared/src/commonMain/composeResources/files/content/en_ulb.zip",
            hint = "run ./gradlew :shared:generateEmbeddedSourcesManifest"
        )

        /** The old app's chunk definitions: book slug to chapter to its chunks, in order. */
        val legacyChunks: Map<String, Map<Int, List<IntRange>>> by lazy {
            val file = repoFile("app-recorder/src/androidMain/assets/${UlbTemplate.LEGACY_CHUNKS_ASSET}")
            Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, chapters) ->
                chapters.jsonObject.entries.associate { (chapter, ranges) ->
                    chapter.toInt() to ranges.jsonPrimitive.content.split(",").map { range ->
                        val (start, end) = range.trim().split("-").map(String::toInt)
                        start..end
                    }
                }
            }
        }

        private fun repoFile(path: String, hint: String? = null): File {
            var dir = File(".").absoluteFile
            while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
            return dir.resolve(path).also {
                assertTrue(it.isFile, "not found: $it" + (hint?.let { h -> "; $h" } ?: ""))
            }
        }
    }
}
