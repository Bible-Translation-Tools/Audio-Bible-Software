package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.audio.wav.WavFile
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.WriteDerivedManifest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Importing a project from an **unzipped** container assembled on disk.
 *
 * This is the shape legacy migration hands to import: rather than writing the database itself, it
 * writes a legacy project out as a dialect backup and imports it, so everything import does has one
 * implementation. Nothing is compressed on the way — `ImportProjectUseCase` identifies a container
 * by loading it, and both the resource container and the file reader have directory-backed
 * implementations.
 *
 * These pin the parts of that contract a directory can break where a zip does not — listing an
 * absent directory throws instead of yielding nothing — and the parts migration depends on but
 * cannot see: that a take's filename is what binds it to a verse, and that `selected.txt` is what
 * makes a take selected.
 */
class ImportStagedDirectoryTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun environment(): IntegrationEnvironment =
        IntegrationEnvironment.create().also {
            env = it
            it.import("en_ulb.zip")
        }

    /**
     * An environment whose only ULB text bridges verses 1 and 2 into one unit — the shape the
     * recorder's chunk-mode source carries for every chunk.
     */
    private fun chunkModeEnvironment(): IntegrationEnvironment =
        IntegrationEnvironment.create().also {
            env = it
            it.import(it.withVersion("en_ulb.zip", CHUNK_VERSION, setOf(BOOK), bridgeFirstTwoVerses = true))
        }

    /** A take whose every sample carries [id], so audio can be traced to the file it came from. */
    private fun wav(dir: File, name: String, id: Int, frames: Int = 100): File {
        dir.mkdirs()
        val file = File(dir, name)
        WavFile(file, 1, 44100, 16).let { w -> w.writer().use { it.write(ByteArray(frames * 2) { id.toByte() }) } }
        return file
    }

    /**
     * The container migration stages: a manifest naming the target language, source and book, the
     * resumable-project marker, the project mode, an empty source directory, and one take per verse
     * under the takes directory.
     *
     * @param verses verse number to the take numbers staged for it
     * @param selected verse numbers whose take 1 is listed in `selected.txt`
     */
    private fun stage(
        env: IntegrationEnvironment,
        verses: Map<Int, List<Int>>,
        selected: Set<Int> = emptySet(),
        withSourceDir: Boolean = true,
        sourceContainer: File? = null,
        sourceMetadata: ResourceMetadata? = null
    ): File {
        val dir = File(env.directoryProvider.tempDirectory, "staged-${System.nanoTime()}")
        dir.mkdirs()

        val source = env.sourceBook(BOOK)
        val metadata = sourceMetadata ?: source.resourceContainer!!
        WriteDerivedManifest().execute(
            dir = dir,
            targetLanguage = env.language(TARGET_LANGUAGE),
            sourceMetadata = metadata,
            bookSlug = BOOK,
            bookTitle = source.titleKey,
            bookSort = 1
        )

        val takeDir = File(dir, "${RcConstants.TAKE_DIR}/c01")
        val selectedLines = mutableListOf<String>()
        verses.forEach { (verse, takeNumbers) ->
            takeNumbers.forEach { number ->
                val name = takeName(verse, number)
                wav(takeDir, name, id = verse)
                if (verse in selected && number == 1) selectedLines += "c01/$name"
            }
        }

        File(dir, RcConstants.SELECTED_TAKES_FILE).writeText(selectedLines.joinToString("\n"))
        File(dir, RcConstants.PROJECT_MODE_FILE).writeText("""{"mode":"DIALECT"}""")
        if (withSourceDir) {
            File(dir, RcConstants.SOURCE_DIR).mkdirs()
            sourceContainer?.copyTo(File(dir, "${RcConstants.SOURCE_DIR}/en_ulb.zip"))
        }
        return dir
    }

    private fun takeName(verse: Int, number: Int) =
        "%s_ulb_%s_c01_v%02d_t%d.wav".format(TARGET_LANGUAGE, BOOK, verse, number)

    /**
     * Verse number to the take filenames registered against it, for chapter 1 of the project.
     *
     * Read from the database rather than through a workbook, whose take relays are populated when
     * it is constructed: a workbook opened around an import may or may not have seen the takes it
     * inserted, which makes any assertion over it depend on when it was built.
     *
     * Bridged filler rows are excluded, so a range appears once under its first verse — the same
     * view of a chapter's units the app shows.
     */
    private fun importedTakes(env: IntegrationEnvironment): Map<Int, List<String>> {
        val chapter = chapterOne(env)
        val takes = env.koin.get<ITakeRepository>()
        return env.koin.get<IContentRepository>().getByCollection(chapter).blockingGet()
            .filter { it.type == ContentType.TEXT && !it.bridged }
            .sortedBy { it.start }
            .associate { content ->
                content.start to takes.getByContent(content, includeDeleted = false)
                    .blockingGet()
                    .map { it.filename }
                    .sorted()
            }
            .filterValues { it.isNotEmpty() }
    }

    /** The verse ranges of chapter 1's units, in order. */
    private fun unitRanges(env: IntegrationEnvironment): List<Pair<Int, Int>> =
        env.koin.get<IContentRepository>().getByCollection(chapterOne(env)).blockingGet()
            .filter { it.type == ContentType.TEXT && !it.bridged }
            .sortedBy { it.start }
            .map { it.start to it.end }

    private fun chapterOne(env: IntegrationEnvironment): Collection =
        env.childrenOf(env.derivedProjects().single { it.slug == BOOK }).single { it.sort == 1 }

    @Test
    fun `a directory container imports without being zipped`() {
        val env = environment()

        val result = env.importer.import(stage(env, verses = mapOf(1 to listOf(1))), null, null).blockingGet()

        assertEquals(ImportResult.SUCCESS, result)
        assertEquals(1, env.derivedProjects().count { it.slug == BOOK }, "one project per book")
    }

    @Test
    fun `each take lands on the verse its filename names`() {
        // The filename is the only thing binding a take to a unit: import parses the chapter and
        // verse out of it and matches them against the derived project's content rows.
        val env = environment()

        env.importer.import(stage(env, verses = mapOf(1 to listOf(1), 5 to listOf(1), 24 to listOf(1))), null, null)
            .blockingGet()

        assertEquals(
            mapOf(
                1 to listOf(takeName(1, 1)),
                5 to listOf(takeName(5, 1)),
                24 to listOf(takeName(24, 1))
            ),
            importedTakes(env)
        )
    }

    @Test
    fun `several takes of one verse all import, keeping their numbers`() {
        // What merging legacy projects produces: `reg`, `udb` and `ulb` takes of one verse arrive as
        // takes 1, 2 and 3 of a single unit.
        val env = environment()

        env.importer.import(stage(env, verses = mapOf(1 to listOf(1, 2, 3))), null, null).blockingGet()

        assertEquals(
            mapOf(1 to listOf(takeName(1, 1), takeName(1, 2), takeName(1, 3))),
            importedTakes(env)
        )
    }

    @Test
    fun `selected takes file decides which take is selected`() {
        val env = environment()
        val staged = stage(env, verses = mapOf(1 to listOf(1), 2 to listOf(1)), selected = setOf(1))

        env.importer.import(staged, null, null).blockingGet()

        val content = env.koin.get<IContentRepository>().getByCollection(chapterOne(env)).blockingGet()
            .filter { it.type == ContentType.TEXT && !it.bridged }
        assertEquals(
            takeName(1, 1),
            content.single { it.start == 1 }.selectedTake?.filename,
            "verse 1 was listed in selected.txt"
        )
        assertEquals(
            null,
            content.single { it.start == 2 }.selectedTake?.filename,
            "verse 2 was not"
        )
    }

    @Test
    fun `an empty source directory is enough, but an absent one is not`() {
        // Import lists the source directory to merge any container inside it. On a zip a missing
        // directory lists as nothing; on a directory it throws, which fails the whole import — so
        // an assembled container has to create it even with nothing to put in it.
        val env = environment()

        assertEquals(
            ImportResult.SUCCESS,
            env.importer.import(stage(env, verses = mapOf(1 to listOf(1))), null, null).blockingGet(),
            "the source is already in the database, so the directory can be empty"
        )

        val withoutSourceDir = stage(env, verses = mapOf(2 to listOf(1)), withSourceDir = false)
        assertTrue(
            !File(withoutSourceDir, RcConstants.SOURCE_DIR).exists(),
            "the fixture must not create it"
        )
        assertEquals(
            ImportResult.FAILED,
            env.importer.import(withoutSourceDir, null, null).blockingGet()
        )
    }

    @Test
    fun `a take named for a bridged range lands on that range's unit`() {
        // How a chunk-mode project migrates: its source's units are bridged ranges, and a take named
        // after a range's first verse binds to the one unit spanning it — so the project shows the
        // chunks it was recorded in rather than single verses.
        val env = chunkModeEnvironment()

        val result = env.importer.import(stage(env, verses = mapOf(1 to listOf(1, 2))), null, null).blockingGet()

        assertEquals(ImportResult.SUCCESS, result)
        assertEquals(
            mapOf(1 to listOf(takeName(1, 1), takeName(1, 2))),
            importedTakes(env),
            "both takes belong to the unit spanning verses 1-2"
        )
        assertEquals(
            listOf(1 to 2),
            unitRanges(env).take(1),
            "the project's first unit is the bridged range, not verse 1 alone"
        )
    }

    @Test
    fun `a backup carrying its source derives from that source, not the one already installed`() {
        // The bug this guards: every install already has the plain `ulb`, and the importer's source
        // lookup matches identifier and language while ignoring the version. A backup that only
        // *names* its source is bound to that plain text — a chunk-mode project then derives verse by
        // verse and the chunking is lost. Carrying the container makes import read it first, and the
        // source it resolves is the one it just imported.
        val env = environment()
        val chunkText = env.withVersion("en_ulb.zip", CHUNK_VERSION, setOf(BOOK), bridgeFirstTwoVerses = true)
        assertEquals(ImportResult.SUCCESS, env.importAsNewSource(chunkText))
        assertEquals(
            listOf("12" to "ulb", CHUNK_VERSION to "ulb"),
            env.sourceIdentities().map { it.second to it.first }.sortedBy { it.first },
            "both texts are installed, sharing an identifier"
        )
        val chunkMetadata = sourceWithVersion(env, CHUNK_VERSION).resourceContainer!!

        env.importer.import(
            stage(
                env,
                verses = mapOf(1 to listOf(1)),
                sourceContainer = chunkText,
                sourceMetadata = chunkMetadata
            ),
            null,
            null
        ).blockingGet()

        assertEquals(
            listOf(1 to 2, 3 to 3, 4 to 4),
            unitRanges(env).take(3),
            "the project's units are the bridged ones, so it derived from the carried source"
        )
    }

    @Test
    fun `a backup that only names its source derives from the one already installed`() {
        // The other half, kept so the reason the container is carried stays visible.
        val env = environment()
        val chunkText = env.withVersion("en_ulb.zip", CHUNK_VERSION, setOf(BOOK), bridgeFirstTwoVerses = true)
        env.importAsNewSource(chunkText)
        val chunkMetadata = sourceWithVersion(env, CHUNK_VERSION).resourceContainer!!

        env.importer.import(
            stage(env, verses = mapOf(1 to listOf(1)), sourceMetadata = chunkMetadata),
            null,
            null
        ).blockingGet()

        assertEquals(
            listOf(1 to 1, 2 to 2, 3 to 3),
            unitRanges(env).take(3),
            "bound to the plain ulb despite the manifest naming the bridged version"
        )
    }

    private fun sourceWithVersion(env: IntegrationEnvironment, version: String): Collection =
        env.koin.get<ICollectionRepository>().getSourceProjects().blockingGet()
            .single { it.slug == BOOK && it.resourceContainer?.version == version }

    private companion object {
        /** One chapter, 25 verses. */
        const val BOOK = "jud"

        /** Any language other than the source's, so the project derives as a dialect. */
        const val TARGET_LANGUAGE = "aa"

        /** The version the recorder gives its chunk-mode text. */
        const val CHUNK_VERSION = "12-chunk"
    }
}
