package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrateLegacyProjects.Result
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The whole migration, from the old app's storage to verified projects, and its journal. */
class MigrateLegacyProjectsTest {

    private val env = LegacyMigrationEnvironment.create()
    private val old = OldAppStorage(env.scratch("old-app/marker").parentFile)
    private val journalFile = env.scratch("app-data/legacy-migration-journal.json")
    private var clock = 0L

    @AfterTest
    fun close() {
        old.close()
        env.close()
    }

    @Test
    fun `with nothing installed there is nothing to do, and no journal`() = runBlocking {
        assertEquals(Result.NothingInstalled, migrate())
        assertFalse(journalFile.exists())
    }

    @Test
    fun `a verse project is migrated, verified, and summarised`() = runBlocking {
        oldVerseProject(verses = 1..3)

        val ran = assertIs<Result.Ran>(migrate())

        val entry = ran.journal.projects.values.single()
        assertEquals(JournalState.VERIFIED, entry.state)
        assertEquals("aa/ulb/jas", entry.location)
        assertEquals(1, entry.chaptersMoved)
        assertEquals(listOf(entry), ran.journal.deletable)
        val take = env.importedProject("jas", "aa").chapterTakes.getValue(1).single()
        assertTrue(take.selected)
        val summary = assertNotNull(MigrationSummaries.pending(ran.journal))
        assertEquals(1 to 1, summary.booksMoved to summary.chaptersMoved)
        assertEquals(ran.journal, MigrationJournal(journalFile).load(), "what the run reports is what it wrote")
    }

    @Test
    fun `a later run leaves finished projects alone`() = runBlocking {
        oldVerseProject(verses = 1..3)
        val first = assertIs<Result.Ran>(migrate())

        val second = assertIs<Result.Ran>(migrate())

        assertEquals(first.journal, second.journal, "nothing was recorded again")
        assertEquals(1, env.importedProject("jas", "aa").chapterTakes.getValue(1).size)
    }

    @Test
    fun `an interrupted project is done again, without adding a take twice`() = runBlocking {
        oldVerseProject(verses = 1..3)
        val first = assertIs<Result.Ran>(migrate())
        // As if the app had stopped after importing, before the verification was recorded.
        rewrite(first.journal) { it.copy(state = JournalState.PLANNED) }

        val second = assertIs<Result.Ran>(migrate())

        val entry = second.journal.projects.values.single()
        assertEquals(JournalState.VERIFIED, entry.state)
        assertEquals(2, entry.attempts)
        assertEquals(1, env.importedProject("jas", "aa").chapterTakes.getValue(1).size)
    }

    @Test
    fun `a project that keeps failing is kept rather than tried again`() = runBlocking {
        oldVerseProject(verses = 1..3)
        val first = assertIs<Result.Ran>(migrate())
        rewrite(first.journal) { it.copy(state = JournalState.PLANNED, attempts = MigrateLegacyProjects.MAX_ATTEMPTS) }

        val second = assertIs<Result.Ran>(migrate())

        val entry = second.journal.projects.values.single()
        assertEquals(JournalState.KEPT, entry.state)
        assertEquals(listOf(JournalReasons.FAILED_REPEATEDLY), entry.reasons.map { it.code })
        assertEquals(listOf(entry), second.journal.deletable, "after the archive choice, like the rest (M10-5)")
    }

    @Test
    fun `with too little disk space a project waits, and is migrated on a later run`() = runBlocking {
        oldVerseProject(verses = 1..3)

        val waiting = assertIs<Result.Ran>(migrate(freeBytes = { 0 })).journal.projects.values.single()
        assertEquals(JournalState.RETRY, waiting.state)
        assertEquals(JournalReasons.LOW_DISK_SPACE, waiting.reasons.single().code)
        assertEquals(0, waiting.attempts, "waiting for space isn't a failed attempt")

        val later = assertIs<Result.Ran>(migrate()).journal.projects.values.single()
        assertEquals(JournalState.VERIFIED, later.state)
    }

    @Test
    fun `a journal that can't be written stops the run, before anything is imported`() = runBlocking {
        oldVerseProject(verses = 1..3)
        // Its folder can't be made: a file is in the way.
        val blocked = env.scratch("blocked").also { it.writeText("a file") }.resolve("journal.json")

        val result = env.migrateLegacyProjects(old, blocked, now = { ++clock }).execute()

        assertIs<Result.JournalFailed>(result)
        assertFalse(blocked.exists())
        assertTrue(runCatching { env.importedProject("jas", "aa") }.isFailure, "no project was imported")
    }

    @Test
    fun `a chunk project waits for a version that migrates chunks`() = runBlocking {
        oldVerseProject(verses = 1..3, mode = "chunk")

        val ran = assertIs<Result.Ran>(migrate())

        val entry = ran.journal.projects.values.single()
        assertEquals(JournalState.DEFERRED, entry.state)
        assertEquals(JournalReasons.TEMPLATE_NOT_ENABLED, entry.reasons.single().code)
        assertTrue(ran.journal.deletable.isEmpty(), "never deleted while it waits")
        assertEquals(listOf(entry), MigrationSummaries.pending(ran.journal)?.waiting)
    }

    @Test
    fun `a project missing a take file is migrated, but kept`() = runBlocking {
        oldVerseProject(verses = 1..3, missing = setOf(2))

        val ran = assertIs<Result.Ran>(migrate())

        val entry = ran.journal.projects.values.single()
        assertEquals(JournalState.KEPT, entry.state)
        assertTrue(entry.imported)
        assertTrue(JournalReasons.TAKE_FILE_MISSING in entry.reasons.map { it.code })
        assertEquals(listOf(entry), ran.journal.deletable, "after the archive choice, like the rest (M10-5)")
        assertEquals(1, env.importedProject("jas", "aa").chapterTakes.getValue(1).size)
        assertEquals(listOf(entry), MigrationSummaries.pending(ran.journal)?.moved)
    }

    @Test
    fun `an unreadable journal is set aside, and the run starts afresh`() = runBlocking {
        oldVerseProject(verses = 1..3)
        journalFile.writeText("not a journal")

        val ran = assertIs<Result.Ran>(migrate())

        assertEquals(JournalState.VERIFIED, ran.journal.projects.values.single().state)
        assertTrue(journalFile.parentFile.listFiles().orEmpty().any { it.name.contains("unreadable") })
    }

    @Test
    fun `a summary marked as told isn't told again`() = runBlocking {
        oldVerseProject(verses = 1..3)
        val ran = assertIs<Result.Ran>(migrate())
        val summary = assertNotNull(MigrationSummaries.pending(ran.journal))

        val told = MigrationSummaries.markReported(ran.journal, summary)

        assertNull(MigrationSummaries.pending(told))
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private suspend fun migrate(freeBytes: () -> Long = { Long.MAX_VALUE }): Result =
        env.migrateLegacyProjects(old, journalFile, freeBytes, now = { ++clock }).execute()

    private fun rewrite(journal: JournalData, change: (JournalEntry) -> JournalEntry) =
        MigrationJournal(journalFile).write(journal.copy(projects = journal.projects.mapValues { change(it.value) }))

    /** An Afar ULB project of James 1 in the old app, one selected take per verse of [verses]. */
    private fun oldVerseProject(verses: IntRange, mode: String = "verse", missing: Set<Int> = emptySet()) {
        val anthology = old.anthology("nt")
        val project = old.project(
            target = old.language("aa"),
            book = old.book("jas", 59, anthology),
            version = old.version("ulb"),
            mode = old.mode(anthology, mode),
            source = old.language("en")
        )
        val chapter = old.chapter(project, 1)
        verses.forEach { verse ->
            val unit = old.unit(project, chapter, verse)
            val name = OldAppStorage.takeName("aa", "ulb", 59, "jas", 1, verse..verse, 1)
            if (verse !in missing) {
                LegacyTakeWav.write(
                    old.takeFile("aa", "ulb", "jas", 1, name), 400 + verse, seed = verse,
                    cues = listOf("$verse" to 0),
                    metadataJson = LegacyTakeWav.metadataJson(mode, verse, verse)
                )
            }
            old.choose(unit, old.take(unit, 1, name, timestamp = 1_600_000_000_000))
        }
    }
}
