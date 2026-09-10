package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.audio.wav.WavFile
import org.bibletranslationtools.otter.common.data.primitives.ContentType
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
        withSourceDir: Boolean = true
    ): File {
        val dir = File(env.directoryProvider.tempDirectory, "staged-${System.nanoTime()}")
        dir.mkdirs()

        val source = env.sourceBook(BOOK)
        val metadata = source.resourceContainer!!
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
        if (withSourceDir) File(dir, RcConstants.SOURCE_DIR).mkdirs()
        return dir
    }

    private fun takeName(verse: Int, number: Int) =
        "%s_ulb_%s_c01_v%02d_t%d.wav".format(TARGET_LANGUAGE, BOOK, verse, number)

    /** Verse number to the take filenames registered against it, for chapter 1 of the project. */
    private fun importedTakes(env: IntegrationEnvironment): Map<Int, List<String>> {
        val project = env.derivedProjects().single { it.slug == BOOK }
        val chapter = env.childrenOf(project).single { it.sort == 1 }
        val workbook = env.workbook(project)
        return try {
            workbook.target.chapters.toList().blockingGet()
                .single { it.sort == chapter.sort }
                .chunks.blockingGet()
                .filter { it.contentType == ContentType.TEXT }
                .associate { chunk ->
                    chunk.start to chunk.audio.getAllTakes().map { it.name }.sorted()
                }
                .filterValues { it.isNotEmpty() }
        } finally {
            env.closeWorkbook(workbook)
        }
    }

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
    fun `a take named after a bridged verse binds to no unit`() {
        // Jude's source bridges its last two verses, so `24..25` is one unit starting at 24 and the
        // row for 25 is a filler standing in for it. A take named v25 matches that filler: it
        // imports, reports no error, and appears against no unit — which is why a staged take is
        // named after the unit covering its verse rather than after the verse.
        val env = environment()

        val result = env.importer.import(stage(env, verses = mapOf(25 to listOf(1))), null, null).blockingGet()

        assertEquals(ImportResult.SUCCESS, result, "nothing reports this as a failure")
        assertEquals(emptyMap(), importedTakes(env), "the take is bound to a bridged filler row")
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

        val project = env.derivedProjects().single { it.slug == BOOK }
        val chapter = env.childrenOf(project).single { it.sort == 1 }
        val workbook = env.workbook(project)
        try {
            val chunks = workbook.target.chapters.toList().blockingGet()
                .single { it.sort == chapter.sort }
                .chunks.blockingGet()
                .filter { it.contentType == ContentType.TEXT }
            assertEquals(
                takeName(1, 1),
                chunks.single { it.start == 1 }.audio.getSelectedTake()?.name,
                "verse 1 was listed in selected.txt"
            )
            assertEquals(
                null,
                chunks.single { it.start == 2 }.audio.getSelectedTake()?.name,
                "verse 2 was not"
            )
        } finally {
            env.closeWorkbook(workbook)
        }
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

    private companion object {
        /** One chapter, 25 verses. */
        const val BOOK = "jud"

        /** Any language other than the source's, so the project derives as a dialect. */
        const val TARGET_LANGUAGE = "aa"
    }
}
