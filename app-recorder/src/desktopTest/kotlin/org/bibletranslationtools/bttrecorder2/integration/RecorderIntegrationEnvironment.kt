package org.bibletranslationtools.bttrecorder2.integration

import io.reactivex.Observable
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.languages.ImportLanguages
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.BackupProjectExporter
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.initialization.InitializeVersification
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.bibletranslationtools.shared.di.koin.appDatabaseModule
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real database, directory tree and Koin graph for the recorder's own conversions to run against.
 *
 * The recorder-side counterpart of `:shared`'s `IntegrationEnvironment`, which lives in a test
 * compilation the app modules cannot depend on. It composes the production graph
 * ([sharedCommonModules] + [appDatabaseModule]) over a temp root, seeds versification and languages
 * the way `InitializeApp` does, and imports the ULB fixture `:shared`'s integration tests use —
 * trimmed to the books a test asks for, so a test costs a fraction of a second rather than a
 * whole-Bible import.
 *
 * Koin's global state means these tests cannot run in parallel with each other, and [close] is not
 * optional.
 */
class RecorderIntegrationEnvironment private constructor(
    private val tempRoot: File,
    val koin: Koin
) : AutoCloseable {

    val directoryProvider: IDirectoryProvider = koin.get()

    /** The real backup exporter, for tests that assert what an export actually contains. */
    val backupExporter: BackupProjectExporter get() = koin.get()

    /** Imports the English ULB fixture trimmed to [books], asserting the import succeeded. */
    fun importUlb(vararg books: String): RecorderIntegrationEnvironment {
        val trimmed = File(tempRoot, "en_ulb-${books.joinToString("-")}.zip")
        trimToBooks(ulbFixture(), trimmed, books.toSet())
        val result = koin.get<RCImporterFactory>().makeImporter().import(trimmed).blockingGet()
        assertEquals(ImportResult.SUCCESS, result, "importing ${trimmed.name}")
        return this
    }

    /**
     * @param deriveProjectFromVerses whether verse rows are derived into the target; not inferred
     *   from [mode], the recorder passes both explicitly.
     */
    fun createProject(
        sourceProject: Collection,
        targetLanguage: Language,
        mode: ProjectMode? = null,
        deriveProjectFromVerses: Boolean = false
    ): Collection = koin.get<CreateProject>()
        .create(sourceProject, targetLanguage, mode, resourceId = null, deriveProjectFromVerses)
        .blockingGet()

    /** The open workbook for a derived project. */
    fun workbook(derived: Collection): Workbook =
        koin.get<IWorkbookRepository>().getWorkbook(derived).blockingGet()
            ?: error("could not open a workbook for ${derived.slug}")

    fun closeWorkbook(workbook: Workbook) {
        koin.get<IWorkbookRepository>().closeWorkbook(workbook)
    }

    /** An imported source book by slug, e.g. "jud". */
    fun sourceBook(slug: String): Collection {
        val projects = koin.get<ICollectionRepository>().getSourceProjects().blockingGet()
        return projects.firstOrNull { it.slug == slug }
            ?: error("no source project '$slug'; imported: ${projects.map { it.slug }.sorted()}")
    }

    fun language(slug: String): Language = koin.get<ILanguageRepository>().getBySlug(slug).blockingGet()

    override fun close() {
        stopKoin()
        tempRoot.deleteRecursively()
    }

    companion object {
        fun create(): RecorderIntegrationEnvironment {
            // A harness test that did not stop Koin would otherwise fail this one at startKoin.
            GlobalContext.getOrNull()?.let { stopKoin() }
            val tempRoot = File.createTempFile("recorder-integration", "").let {
                it.delete()
                it.mkdirs()
                it
            }
            val provider = DesktopDirectoryProvider(
                appName = "RecorderIntegrationTest",
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

            return RecorderIntegrationEnvironment(tempRoot, koin).apply {
                initializeVersification()
                importLanguages()
            }
        }

        /** Versification rows are a precondition for the importer's pre-allocation; see `InitializeApp`. */
        private fun RecorderIntegrationEnvironment.initializeVersification() {
            Observable.create<ProgressStatus> { emitter ->
                koin.get<InitializeVersification>().exec(emitter).blockingAwait()
                emitter.onComplete()
            }.blockingSubscribe()
        }

        /** Language rows are a precondition for importing anything that names its language by slug. */
        private fun RecorderIntegrationEnvironment.importLanguages() {
            repoFile("shared/src/commonMain/composeResources/files/content/langnames.json")
                .inputStream()
                .use { stream -> koin.get<ImportLanguages>().import(stream).blockingAwait() }
        }

        private fun ulbFixture(): File =
            repoFile("shared/src/desktopIntegrationTest/resources/resource-containers/en_ulb.zip")

        private fun repoFile(path: String): File =
            File(repoRoot(), path).also { assertTrue(it.isFile, "$path not found at ${it.absolutePath}") }

        /** Gradle runs tests with the working directory set to the module dir. */
        private fun repoRoot(): File {
            var dir = File(".").absoluteFile
            while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile
            }
            return dir
        }

        private val usfmName = Regex("""(?:^|/)\d+-(\w+)\.usfm$""")
        private val projectId = Regex("""^\s*identifier:\s*'(\w+)'\s*$""")

        /** Copies [source] to [target] keeping only [books]' USFM and the matching `projects:` entries. */
        private fun trimToBooks(source: File, target: File, books: Set<String>) {
            ZipFile(source).use { zip ->
                ZipOutputStream(target.outputStream().buffered()).use { out ->
                    zip.entries().asSequence().forEach { entry ->
                        if (entry.isDirectory) return@forEach
                        val slug = usfmName.find(entry.name)?.groupValues?.get(1)?.lowercase()
                        if (slug != null && slug !in books) return@forEach
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        out.putNextEntry(ZipEntry(entry.name))
                        out.write(
                            if (entry.name.endsWith("manifest.yaml")) {
                                trimManifest(bytes.decodeToString(), books).toByteArray()
                            } else {
                                bytes
                            }
                        )
                        out.closeEntry()
                    }
                }
            }
        }

        /** Keeps only the `projects:` blocks whose identifier is in [books]; each block starts with `  -`. */
        private fun trimManifest(manifest: String, books: Set<String>): String {
            val lines = manifest.split("\n")
            val header = lines.indexOfFirst { it.trimEnd() == "projects:" }
            if (header < 0) return manifest

            val kept = mutableListOf<String>()
            var block = mutableListOf<String>()
            fun flush() {
                val slug = block.firstNotNullOfOrNull { projectId.find(it)?.groupValues?.get(1) }
                if (slug != null && slug in books) kept += block
                block = mutableListOf()
            }
            for (index in (header + 1) until lines.size) {
                if (lines[index].trimEnd() == "  -") flush()
                block += lines[index]
            }
            flush()
            return (lines.take(header + 1) + kept).joinToString("\n")
        }
    }
}
