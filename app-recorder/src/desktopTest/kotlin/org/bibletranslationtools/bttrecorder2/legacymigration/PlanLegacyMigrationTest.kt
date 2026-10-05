package org.bibletranslationtools.bttrecorder2.legacymigration

import org.bibletranslationtools.bttrecorder2.legacymigration.LegacyChunking.CHUNKS
import org.bibletranslationtools.bttrecorder2.legacymigration.LegacyChunking.VERSE_BY_VERSE
import org.bibletranslationtools.bttrecorder2.legacymigration.LegacyProjectPlan.Disposition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlanLegacyMigrationTest {

    // JAS 1 as the two templates divide it: the old app's chunks, and one verse at a time.
    private val jas1Chunks = listOf(1..3, 4..5, 6..8, 9..11, 12..13, 14..16, 17..18, 19..21, 22..25, 26..27)
    private val templates = TemplateChunks(
        mapOf(
            UlbTemplate.VERSES to mapOf("jas" to mapOf(1 to (1..27).map { it..it }, 2 to (1..26).map { it..it })),
            UlbTemplate.CHUNKS to mapOf("jas" to mapOf(1 to jas1Chunks, 2 to listOf(1..4, 5..7)))
        )
    )

    private val plan = PlanLegacyMigration()

    // ── templates and pieces ───────────────────────────────────────────────────────────────

    @Test
    fun `a chunked project is planned on the chunk template, its selected takes in verse order`() {
        val legacy = project(1, CHUNKS, chapter(1, chunk(4, take(4..5)), chunk(1, take(1..3))))

        val result = plan.execute(listOf(legacy), templates)

        val project = result.projects.single()
        assertEquals(MigrationProjectKey("aa", "jas", UlbTemplate.CHUNKS), project.key)
        assertEquals(listOf(1..3, 4..5), project.chapters.single().selected.pieces.map { it.verses })
        assertEquals(Disposition.Migrated(project.key), result.legacyProjects.single().disposition)
        assertTrue(result.legacyProjects.single().deletableOnceVerified)
    }

    @Test
    fun `a verse-by-verse project is planned on the verse template`() {
        val legacy = project(1, VERSE_BY_VERSE, chapter(1, chunk(1, take(1..1)), chunk(2, take(2..2))))

        val project = plan.execute(listOf(legacy), templates).projects.single()

        assertEquals(UlbTemplate.VERSES, project.key.template)
        assertEquals(listOf(1..1, 2..2), project.chapters.single().selected.pieces.map { it.verses })
    }

    @Test
    fun `a chunk of one verse is fine in a chunked project`() {
        val templates = TemplateChunks(mapOf(UlbTemplate.CHUNKS to mapOf("jas" to mapOf(5 to listOf(9..11, 12..12)))))
        val legacy = project(1, CHUNKS, chapter(5, chunk(12, take(12..12))))

        val project = plan.execute(listOf(legacy), templates).projects.single()

        assertEquals(listOf(12..12), project.chapters.single().selected.pieces.map { it.verses })
    }

    // ── combining version labels ───────────────────────────────────────────────────────────

    @Test
    fun `version labels of one book and language become one project, each label its own chapter take`() {
        val ulb = project(1, CHUNKS, chapter(1, chunk(1, take(1..3))), version = "ulb")
        val reg = project(2, CHUNKS, chapter(1, chunk(1, take(1..3)), chunk(4, take(4..5))), version = "reg")

        val result = plan.execute(listOf(ulb, reg), templates)

        val chapter = result.projects.single().chapters.single()
        assertEquals(setOf("ulb", "reg"), chapter.takes.map { it.versionLabel }.toSet())
        assertEquals("reg", chapter.selected.versionLabel, "the most complete is selected")
        assertEquals(listOf(1, 2), result.projects.single().legacyProjectIds.sorted())
    }

    @Test
    fun `when labels are equally complete, the one matching the project's identifier is selected`() {
        fun selectedLabel(target: String): String {
            val ulb = project(1, CHUNKS, chapter(1, chunk(1, take(1..3))), version = "ulb", target = target)
            val reg = project(2, CHUNKS, chapter(1, chunk(1, take(1..3))), version = "reg", target = target)
            return plan.execute(listOf(ulb, reg), templates).projects.single().chapters.single().selected.versionLabel
        }
        assertEquals("reg", selectedLabel("aa"), "a project in another language is a reg project")
        assertEquals("ulb", selectedLabel("en"), "an English project from the ULB is a ulb project")
    }

    @Test
    fun `verse-by-verse and chunked projects of one book and language become separate projects`() {
        val verses = project(1, VERSE_BY_VERSE, chapter(1, chunk(1, take(1..1))), version = "ulb")
        val chunks = project(2, CHUNKS, chapter(1, chunk(1, take(1..3))), version = "reg")

        val keys = plan.execute(listOf(verses, chunks), templates).projects.map { it.key.template }

        assertEquals(setOf(UlbTemplate.VERSES, UlbTemplate.CHUNKS), keys.toSet())
    }

    @Test
    fun `contributors are merged without duplicates, and differing source languages are reported`() {
        val ulb = project(1, CHUNKS, chapter(1, chunk(1, take(1..3))), version = "ulb", contributors = "Ana, Ben", source = "en")
        val reg = project(2, CHUNKS, chapter(1, chunk(4, take(4..5))), version = "reg", contributors = "Ben;Cy", source = "fr")

        val result = plan.execute(listOf(ulb, reg), templates)

        assertEquals(listOf("Ana", "Ben", "Cy").sorted(), result.projects.single().contributors.sorted())
        result.legacyProjects.forEach { legacy ->
            val combined = legacy.problems.filterIsInstance<MigrationProblem.SourceLanguageCombined>().single()
            assertEquals(setOf("en", "fr"), combined.sourceLanguages)
            assertTrue(legacy.deletableOnceVerified, "reporting it loses nothing")
        }
    }

    // ── ranges ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a range the template doesn't have exactly is left out, never relabelled, and keeps the old files`() {
        val legacy = project(1, CHUNKS, chapter(1, chunk(1, take(1..3)), chunk(4, take(4..6))))

        val result = plan.execute(listOf(legacy), templates)

        assertEquals(listOf(1..3), result.projects.single().chapters.single().selected.pieces.map { it.verses })
        val problem = result.legacyProjects.single().problems.single()
        assertEquals(MigrationProblem.RangeNotInTemplate(1, 4..6, UlbTemplate.CHUNKS), problem)
        assertFalse(result.legacyProjects.single().deletableOnceVerified)
    }

    @Test
    fun `a take whose filename and metadata disagree is left out`() {
        val bad = take(4..5, metadata = LegacyTakeMetadata(CHUNKS, 4..6))
        val legacy = project(1, CHUNKS, chapter(1, chunk(1, take(1..3)), chunk(4, bad)))

        val result = plan.execute(listOf(legacy), templates)

        assertEquals(listOf(1..3), result.projects.single().chapters.single().selected.pieces.map { it.verses })
        assertIs<MigrationProblem.RangeDisagrees>(result.legacyProjects.single().problems.single())
        assertFalse(result.legacyProjects.single().deletableOnceVerified)
    }

    @Test
    fun `the metadata's range is used when the filename doesn't parse, and neither is reported`() {
        val fromMetadata = take(null, metadata = LegacyTakeMetadata(CHUNKS, 4..5))
        val unknown = take(null)
        val legacy = project(1, CHUNKS, chapter(1, chunk(4, fromMetadata), chunk(6, unknown)))

        val result = plan.execute(listOf(legacy), templates)

        assertEquals(listOf(4..5), result.projects.single().chapters.single().selected.pieces.map { it.verses })
        assertIs<MigrationProblem.RangeUnknown>(result.legacyProjects.single().problems.single())
    }

    @Test
    fun `a take that doesn't start at its chunk's first verse is left out`() {
        val legacy = project(1, CHUNKS, chapter(1, chunk(1, take(1..3)), chunk(4, take(6..8))))

        val problems = plan.execute(listOf(legacy), templates).legacyProjects.single().problems

        assertIs<MigrationProblem.RangeDisagrees>(problems.single())
    }

    @Test
    fun `a take whose file is gone is left out, and keeps the old files`() {
        val legacy = project(1, CHUNKS, chapter(1, chunk(1, take(1..3)), chunk(4, take(4..5, onDisk = false))))

        val result = plan.execute(listOf(legacy), templates)

        assertEquals(listOf(1..3), result.projects.single().chapters.single().selected.pieces.map { it.verses })
        assertIs<MigrationProblem.TakeFileMissing>(result.legacyProjects.single().problems.single())
        assertFalse(result.legacyProjects.single().deletableOnceVerified)
    }

    // ── selection ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the selected take is used`() {
        val first = take(1..3, number = 1)
        val second = take(1..3, number = 2)
        val legacy = project(1, CHUNKS, chapter(1, chunk(1, first, second, selected = first)))

        val piece = plan.execute(listOf(legacy), templates).projects.single().chapters.single().selected.pieces.single()

        assertEquals(first, piece.take)
    }

    @Test
    fun `without a usable selection, the old app's rule picks a take, and it's reported`() {
        val low = take(1..3, rating = 1, recordedAt = 300)
        val highOld = take(1..3, rating = 3, recordedAt = 100)
        val highNew = take(1..3, rating = 3, recordedAt = 200)
        val none = chunk(1, low, highOld, highNew, selected = null)
        val dangling = LegacyChunk(id = 99, startVerse = 4, selectedTakeId = 12345, takes = listOf(take(4..5)))
        val legacy = project(1, CHUNKS, chapter(1, none, dangling))

        val result = plan.execute(listOf(legacy), templates)

        assertEquals(highNew, result.projects.single().chapters.single().selected.pieces.first().take)
        assertEquals(2, result.legacyProjects.single().problems.count { it is MigrationProblem.SelectionChosen })
        assertTrue(result.legacyProjects.single().deletableOnceVerified, "choosing a take loses nothing")
    }

    // ── how the project was divided ────────────────────────────────────────────────────────

    @Test
    fun `without a mode, the mode written into the takes decides`() {
        val legacy = project(1, null, chapter(1, chunk(1, take(1..1, metadata = LegacyTakeMetadata(CHUNKS, 1..1)))))
        val templates = TemplateChunks(mapOf(UlbTemplate.CHUNKS to mapOf("jas" to mapOf(1 to listOf(1..1, 2..3)))))

        val result = plan.execute(listOf(legacy), templates)

        assertEquals(UlbTemplate.CHUNKS, result.projects.single().key.template)
        assertIs<MigrationProblem.ChunkingInferred>(result.legacyProjects.single().problems.single())
    }

    @Test
    fun `without a mode, a take spanning several verses means chunks`() {
        val legacy = project(1, null, chapter(1, chunk(1, take(1..3))))

        assertEquals(UlbTemplate.CHUNKS, plan.execute(listOf(legacy), templates).projects.single().key.template)
    }

    @Test
    fun `without a mode, single verses are planned verse by verse, and it's reported`() {
        val legacy = project(1, null, chapter(1, chunk(1, take(1..1)), chunk(2, take(2..2))))

        val result = plan.execute(listOf(legacy), templates)

        assertEquals(UlbTemplate.VERSES, result.projects.single().key.template)
        val inferred = assertIs<MigrationProblem.ChunkingInferred>(result.legacyProjects.single().problems.single())
        assertEquals(VERSE_BY_VERSE, inferred.chunking)
    }

    @Test
    fun `a project the mode calls verse by verse but holding a multi-verse take is kept whole`() {
        val legacy = project(1, VERSE_BY_VERSE, chapter(1, chunk(1, take(1..1)), chunk(2, take(2..3))))

        val result = plan.execute(listOf(legacy), templates)

        assertTrue(result.projects.isEmpty())
        assertEquals(Disposition.Kept, result.legacyProjects.single().disposition)
        assertIs<MigrationProblem.ChunkingContradicted>(result.legacyProjects.single().problems.single())
    }

    @Test
    fun `takes recorded in both modes are a contradiction, and the project is kept whole`() {
        val legacy = project(
            1, null,
            chapter(
                1,
                chunk(1, take(1..1, metadata = LegacyTakeMetadata(VERSE_BY_VERSE, 1..1))),
                chunk(4, take(4..5, metadata = LegacyTakeMetadata(CHUNKS, 4..5)))
            )
        )

        val result = plan.execute(listOf(legacy), templates)

        assertEquals(Disposition.Kept, result.legacyProjects.single().disposition)
    }

    // ── what stays behind ──────────────────────────────────────────────────────────────────

    @Test
    fun `projects outside the Bible, English udb projects and books no template has are kept`() {
        val obs = project(1, CHUNKS, chapter(1, chunk(1, take(1..3))), anthology = "obs")
        val englishUdb = project(2, CHUNKS, chapter(1, chunk(1, take(1..3))), version = "udb", target = "en")
        val unknownBook = project(3, CHUNKS, chapter(1, chunk(1, take(1..3))), book = "xyz")

        val result = plan.execute(listOf(obs, englishUdb, unknownBook), templates)

        assertTrue(result.projects.isEmpty())
        val problems = result.legacyProjects.associate { it.legacyProject.id to it.problems.single() }
        assertEquals(MigrationProblem.OutOfScope("obs"), problems[1])
        assertEquals(MigrationProblem.EnglishUdb, problems[2])
        assertEquals(MigrationProblem.BookNotInTemplate("xyz"), problems[3])
        result.legacyProjects.forEach {
            assertEquals(Disposition.Kept, it.disposition)
            assertFalse(it.deletableOnceVerified)
        }
    }

    @Test
    fun `a udb project in another language is migrated`() {
        val legacy = project(1, CHUNKS, chapter(1, chunk(1, take(1..3))), version = "udb", target = "aa")

        assertEquals(1, plan.execute(listOf(legacy), templates).projects.size)
    }

    @Test
    fun `a project with nothing recorded needs nothing migrated, and its files may go`() {
        val legacy = project(1, CHUNKS, chapter(1, LegacyChunk(id = 50, startVerse = 1, selectedTakeId = null, takes = emptyList())))

        val result = plan.execute(listOf(legacy), templates)

        assertTrue(result.projects.isEmpty())
        assertEquals(Disposition.NothingRecorded, result.legacyProjects.single().disposition)
        assertTrue(result.legacyProjects.single().deletableOnceVerified)
    }

    @Test
    fun `a project whose every take is unusable is kept`() {
        val legacy = project(1, CHUNKS, chapter(1, chunk(4, take(4..6))))

        val result = plan.execute(listOf(legacy), templates)

        assertTrue(result.projects.isEmpty())
        assertEquals(Disposition.Kept, result.legacyProjects.single().disposition)
    }

    // ── builders ───────────────────────────────────────────────────────────────────────────

    private var nextId = 1

    private fun take(
        verses: IntRange?,
        rating: Int = 0,
        recordedAt: Long? = null,
        onDisk: Boolean = true,
        metadata: LegacyTakeMetadata? = null,
        number: Int = 1
    ): LegacyTake {
        val id = nextId++
        return LegacyTake(id, number, rating, recordedAt, File("take-$id.wav"), onDisk, verses, metadata)
    }

    private fun chunk(startVerse: Int, vararg takes: LegacyTake, selected: LegacyTake? = takes.firstOrNull()) =
        LegacyChunk(id = nextId++, startVerse = startVerse, selectedTakeId = selected?.id, takes = takes.toList())

    private fun chapter(number: Int, vararg chunks: LegacyChunk) = LegacyChapter(number, chunks.toList())

    private fun project(
        id: Int,
        chunking: LegacyChunking?,
        vararg chapters: LegacyChapter,
        version: String = "ulb",
        target: String = "aa",
        book: String = "jas",
        anthology: String = "nt",
        source: String? = "en",
        contributors: String? = null
    ) = LegacyProject(
        id = id,
        anthology = anthology,
        book = LegacyBook(book, 59),
        targetLanguage = target,
        sourceLanguage = source,
        versionLabel = version,
        chunking = chunking,
        contributors = contributors,
        folder = File("translations/$target/$version/$book"),
        chapters = chapters.toList()
    )
}
