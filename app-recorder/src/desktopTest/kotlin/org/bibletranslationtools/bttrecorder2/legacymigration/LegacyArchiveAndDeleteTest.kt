package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The archive of the old recordings, and the delete that follows the user's choice (M10). */
class LegacyArchiveAndDeleteTest {

    private val root: File = Files.createTempDirectory("old-app").toFile()
    private val old = OldAppStorage(root.resolve("device"))
    private val journal = MigrationJournal(root.resolve("app/logs/legacy_migration_journal.json"))
    private val archive = JournalLegacyArchive(
        journal,
        WriteLegacyArchive(old.storage(), journal),
        DeleteLegacyProjects(old.reader(), old.storage(), journal)
    )

    @AfterTest
    fun clean() {
        old.close()
        root.deleteRecursively()
    }

    // ── the archive ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the archive holds every project's recordings, files nothing names, the database and the journal`() = runBlocking {
        val anthology = old.anthology("nt")
        val james = project("jas", 59, anthology)
        val obs = old.anthology("obs").let { project("01", 1, it, version = "reg") }
        val stray = old.takeFile("aa", "ulb", "jas", 1, "stray.wav").also { it.writeText("audio") }
        journal.write(JournalData())

        val bytes = ByteArrayOutputStream()
        val result = archive.save(bytes)

        val entries = ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toSet()
        }
        val expected = (james.files + obs.files + stray).map { "translations/${it.relativeTo(old.translations).invariantSeparatorsPath}" } +
            "databases/translation_projects" + "legacy_migration_journal.json"
        assertEquals(expected.toSet(), entries)
        assertEquals(entries.size, result.files)
        assertTrue(james.files.all { it.isFile }, "saving deletes nothing")
    }

    // ── the delete ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `nothing is deleted before the archive choice`() = runBlocking {
        val james = project("jas", 59, old.anthology("nt"))
        record(james, JournalState.VERIFIED)

        assertTrue(archive.offerNeeded())
        assertEquals(DeleteLegacyProjects.Result.AwaitingArchiveChoice, DeleteLegacyProjects(old.reader(), old.storage(), journal).execute())
        assertTrue(james.files.all { it.isFile })
    }

    @Test
    fun `after the choice, verified, kept and empty projects go, and waiting ones stay`() = runBlocking {
        val anthology = old.anthology("nt")
        val verified = project("jas", 59, anthology)
        val kept = project("rom", 45, anthology)
        val empty = project("1jn", 62, anthology, verses = IntRange.EMPTY)
        val deferred = project("mrk", 41, anthology)
        val retry = project("jud", 65, anthology)
        record(verified, JournalState.VERIFIED)
        record(kept, JournalState.KEPT)
        record(empty, JournalState.NOTHING_RECORDED)
        record(deferred, JournalState.DEFERRED)
        record(retry, JournalState.RETRY)

        val result = assertIs<DeleteLegacyProjects.Result.Deleted>(archive.choose(saved = false))

        assertEquals(3, result.deleted)
        assertFalse(result.databaseDeleted, "projects remain in it")
        assertTrue((verified.files + kept.files).none { it.exists() })
        assertFalse(old.translations.resolve("aa/ulb/jas").exists(), "the folders it leaves empty go")
        assertTrue((deferred.files + retry.files).all { it.isFile })
        val states = journal.load().projects.values.associate { it.location to it.state }
        assertEquals(JournalState.LEGACY_DELETED, states.getValue("aa/ulb/jas"))
        assertEquals(JournalState.LEGACY_DELETED, states.getValue("aa/ulb/1jn"))
        assertEquals(JournalState.DEFERRED, states.getValue("aa/ulb/mrk"))
        assertFalse(archive.offerNeeded(), "the choice is made once")
        assertEquals(false, journal.load().archive?.saved)
    }

    @Test
    fun `files no take names are left`() = runBlocking {
        val james = project("jas", 59, old.anthology("nt"))
        val stray = old.takeFile("aa", "ulb", "jas", 1, "stray.wav").also { it.writeText("audio") }
        record(james, JournalState.VERIFIED)

        archive.choose(saved = true)

        assertTrue(james.files.none { it.exists() })
        assertTrue(stray.isFile)
    }

    @Test
    fun `a delete that fails keeps the project's state, and is tried again`() = runBlocking {
        val james = project("jas", 59, old.anthology("nt"))
        record(james, JournalState.VERIFIED)
        val chapterFolder = james.files.first().parentFile
        chapterFolder.setWritable(false)

        val first = assertIs<DeleteLegacyProjects.Result.Deleted>(archive.choose(saved = true))
        assertEquals(0 to 1, first.deleted to first.failed)
        assertEquals(JournalState.VERIFIED, journal.load().projects.values.single().state)

        chapterFolder.setWritable(true)
        val again = assertIs<DeleteLegacyProjects.Result.Deleted>(DeleteLegacyProjects(old.reader(), old.storage(), journal).execute())
        assertEquals(1, again.deleted)
        assertEquals(JournalState.LEGACY_DELETED, journal.load().projects.values.single().state)
    }

    @Test
    fun `the old database goes once every project in it has gone`() = runBlocking {
        val anthology = old.anthology("nt")
        record(project("jas", 59, anthology), JournalState.VERIFIED)
        record(project("rom", 45, anthology), JournalState.KEPT)

        val result = assertIs<DeleteLegacyProjects.Result.Deleted>(archive.choose(saved = true))

        assertTrue(result.databaseDeleted)
        assertFalse(old.databaseFile.exists())
        assertTrue(old.translations.isDirectory, "the recordings folder itself is never removed")
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private class Fixture(val project: Long, val location: String, val files: List<File>)

    private val languageIds = mutableMapOf<String, Long>()
    private val versionIds = mutableMapOf<String, Long>()

    /** An Afar project of [book] with chapter 1 and one take per verse of [verses]. */
    private fun project(book: String, number: Int, anthology: Long, version: String = "ulb", verses: IntRange = 1..2): Fixture {
        val project = old.project(
            target = languageIds.getOrPut("aa") { old.language("aa") },
            book = old.book(book, number, anthology),
            version = versionIds.getOrPut(version) { old.version(version) },
            mode = old.mode(anthology, "verse")
        )
        val chapter = old.chapter(project, 1)
        val files = verses.map { verse ->
            val unit = old.unit(project, chapter, verse)
            val name = OldAppStorage.takeName("aa", version, number, book, 1, verse..verse, 1)
            val file = old.takeFile("aa", version, book, 1, name)
            LegacyTakeWav.write(file, 100, seed = verse)
            old.choose(unit, old.take(unit, 1, name))
            file
        }
        return Fixture(project, "aa/$version/$book", files)
    }

    /** Records [fixture]'s project in the journal as [state], as the migration would have. */
    private suspend fun record(fixture: Fixture, state: JournalState) {
        val read = assertIs<LegacyReadResult.Read>(old.reader().read())
        val project = read.projects.single { it.id == fixture.project.toInt() }
        val data = journal.load()
        journal.write(data.with(JournalEntry(project.journalKey, project.id, project.location, state)))
    }
}
