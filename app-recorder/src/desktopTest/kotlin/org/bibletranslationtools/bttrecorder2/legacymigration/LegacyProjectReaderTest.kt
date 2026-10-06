package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyProjectReaderTest {

    private val root: File = Files.createTempDirectory("old-app").toFile()
    private val old = OldAppStorage(root)

    @AfterTest
    fun clean() {
        old.close()
        root.deleteRecursively()
    }

    // ── what is installed ──────────────────────────────────────────────────────────────────

    @Test
    fun `with no database and no recordings, nothing is installed`() {
        assertEquals(LegacyReadResult.NothingInstalled, read())
    }

    @Test
    fun `recordings without a database are reported as unclaimed folders`() {
        old.takeFile("aa", "ulb", "jas", 1, "x.wav").also { it.parentFile.mkdirs(); it.writeText("audio") }

        val read = assertIs<LegacyReadResult.Read>(read())

        assertTrue(read.projects.isEmpty())
        assertEquals(listOf(old.translations.resolve("aa/ulb/jas")), read.unclaimedFolders)
    }

    @Test
    fun `a database of another schema version is unreadable`() {
        old.schemaVersion(3)

        val unreadable = assertIs<LegacyReadResult.Unreadable>(read())

        assertTrue("3" in unreadable.reason, unreadable.reason)
    }

    @Test
    fun `a database that isn't one is unreadable, and nothing throws`() {
        old.databaseFile.parentFile.mkdirs()
        old.databaseFile.writeText("not a database")

        assertIs<LegacyReadResult.Unreadable>(read())
    }

    // ── a project ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a project is read with its book, languages, version, mode and contributors`() {
        val ids = james(mode = "chunk", contributors = "Ana; Ben")

        val project = singleProject()

        assertEquals(ids.project.toInt(), project.id)
        assertEquals("nt", project.anthology)
        assertEquals(LegacyBook("jas", 59), project.book)
        assertEquals("aa", project.targetLanguage)
        assertEquals("en", project.sourceLanguage)
        assertEquals("ulb", project.versionLabel)
        assertEquals(LegacyChunking.CHUNKS, project.chunking)
        assertEquals("Ana; Ben", project.contributors)
        assertEquals(old.translations.resolve("aa/ulb/jas"), project.folder)
    }

    @Test
    fun `a chunk's takes are read with their range from the filename and what the WAV says`() {
        val ids = james(mode = "chunk")
        val unit = old.unit(ids.project, ids.chapter, 4)
        val first = recordedTake(unit, 1, 4..6, rating = 2, timestamp = 1_600_000_000_000, markers = mapOf("5" to 300))
        val second = recordedTake(unit, 2, 4..6, rating = 3)
        old.choose(unit, second)

        val chunk = singleProject().chapters.single().chunks.single()

        assertEquals(4, chunk.startVerse)
        assertEquals(second.toInt(), chunk.selectedTakeId)
        assertEquals(listOf(1, 2), chunk.takes.map { it.number })
        val take = chunk.takes.first()
        assertEquals(first.toInt(), take.id)
        assertEquals(2, take.rating)
        assertEquals(1_600_000_000_000, take.recordedAt)
        assertTrue(take.onDisk)
        assertEquals(4..6, take.verses)
        assertEquals(LegacyTakeMetadata(LegacyChunking.CHUNKS, 4..6, listOf(LegacyMarker("5", 300))), take.metadata)
    }

    @Test
    fun `chapters and chunks come in order`() {
        val ids = james(mode = "verse")
        val chapter2 = old.chapter(ids.project, 2)
        recordedTake(old.unit(ids.project, chapter2, 1), 1, 1..1, chapter = 2, mode = "verse")
        recordedTake(old.unit(ids.project, ids.chapter, 3), 1, 3..3, mode = "verse")
        recordedTake(old.unit(ids.project, ids.chapter, 1), 1, 1..1, mode = "verse")

        val chapters = singleProject().chapters

        assertEquals(listOf(1, 2), chapters.map { it.number })
        assertEquals(listOf(1, 3), chapters.first().chunks.map { it.startVerse })
        assertEquals(LegacyChunking.VERSE_BY_VERSE, chapters.first().chunks.first().takes.single().metadata?.chunking)
    }

    @Test
    fun `a project without a mode row has no chunking`() {
        james(mode = null)

        assertNull(singleProject().chunking)
    }

    // ── takes that aren't as expected ──────────────────────────────────────────────────────

    @Test
    fun `a take whose file is gone is read as not on disk, with its range from the name`() {
        val ids = james(mode = "chunk")
        old.take(old.unit(ids.project, ids.chapter, 1), 1, takeName(1..3, 1))

        val take = singleProject().chapters.single().chunks.single().takes.single()

        assertFalse(take.onDisk)
        assertEquals(1..3, take.verses)
        assertNull(take.metadata)
    }

    @Test
    fun `a take filed in another folder of the book is found by name`() {
        val ids = james(mode = "chunk")
        val name = takeName(1..3, 1)
        old.take(old.unit(ids.project, ids.chapter, 1), 1, name)
        val elsewhere = old.translations.resolve("aa/ulb/jas/misplaced/$name")
        LegacyTakeWav.write(elsewhere, 100, seed = 1)

        val take = singleProject().chapters.single().chunks.single().takes.single()

        assertTrue(take.onDisk)
        assertEquals(elsewhere, take.file)
    }

    @Test
    fun `a take whose name doesn't parse has no range`() {
        val ids = james(mode = "chunk")
        old.take(old.unit(ids.project, ids.chapter, 1), 1, "renamed.wav")

        assertNull(singleProject().chapters.single().chunks.single().takes.single().verses)
    }

    @Test
    fun `a recording that never finished has no metadata`() {
        val ids = james(mode = "chunk")
        val name = takeName(1..3, 1)
        old.take(old.unit(ids.project, ids.chapter, 1), 1, name)
        LegacyTakeWav.write(old.takeFile("aa", "ulb", "jas", 1, name), 300, seed = 1, declaredAudioBytes = 0)

        val take = singleProject().chapters.single().chunks.single().takes.single()

        assertTrue(take.onDisk)
        assertNull(take.metadata)
    }

    @Test
    fun `a take row without a filename is skipped and reported`() {
        val ids = james(mode = "chunk")
        val take = old.take(old.unit(ids.project, ids.chapter, 1), 1, null)

        val read = assertIs<LegacyReadResult.Read>(read())

        assertTrue(read.projects.single().chapters.single().chunks.single().takes.isEmpty())
        assertEquals(SkippedRow("takes", take, "it has no filename"), read.skippedRows.single())
    }

    @Test
    fun `a project whose book row is missing is skipped and reported, and its folder is unclaimed`() {
        val ids = james(mode = "chunk")
        recordedTake(old.unit(ids.project, ids.chapter, 1), 1, 1..3)
        old.execute("DELETE FROM books")

        val read = assertIs<LegacyReadResult.Read>(read())

        assertTrue(read.projects.isEmpty())
        assertEquals("projects", read.skippedRows.single().table)
        assertEquals(listOf(old.translations.resolve("aa/ulb/jas")), read.unclaimedFolders)
    }

    // ── files nothing points at ────────────────────────────────────────────────────────────

    @Test
    fun `files no take points at are listed, and other books' folders are unclaimed`() {
        val ids = james(mode = "chunk")
        recordedTake(old.unit(ids.project, ids.chapter, 1), 1, 1..3)
        val stray = old.takeFile("aa", "ulb", "jas", 1, "stray.wav").also { it.writeText("audio") }
        old.takeFile("aa", "ulb", "rom", 1, "x.wav").also { it.parentFile.mkdirs(); it.writeText("audio") }

        val read = assertIs<LegacyReadResult.Read>(read())

        assertEquals(listOf(stray), read.projects.single().unreferencedFiles)
        assertEquals(listOf(old.translations.resolve("aa/ulb/rom")), read.unclaimedFolders)
    }

    @Test
    fun `reading changes nothing`() {
        val ids = james(mode = "chunk")
        recordedTake(old.unit(ids.project, ids.chapter, 1), 1, 1..3)
        val file = old.takeFile("aa", "ulb", "jas", 1, takeName(1..3, 1))
        val database = old.databaseFile.readBytes()
        val audio = file.readBytes()
        val modified = file.lastModified()

        assertIs<LegacyReadResult.Read>(read())

        assertContentEquals(database, old.databaseFile.readBytes())
        assertContentEquals(audio, file.readBytes())
        assertEquals(modified, file.lastModified())
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private class Ids(val project: Long, val chapter: Long, val anthology: Long)

    /** An Afar ULB project of James, from English, with chapter 1. */
    private fun james(mode: String?, contributors: String? = null): Ids {
        val anthology = old.anthology("nt")
        val book = old.book("jas", 59, anthology)
        val project = old.project(
            target = old.language("aa"),
            book = book,
            version = old.version("ulb"),
            mode = mode?.let { old.mode(anthology, it) },
            source = old.language("en"),
            contributors = contributors
        )
        return Ids(project, old.chapter(project, 1), anthology)
    }

    private fun takeName(verses: IntRange, take: Int, chapter: Int = 1) =
        OldAppStorage.takeName("aa", "ulb", 59, "jas", chapter, verses, take)

    /** A take row and its file, as the old app recorded it. */
    private fun recordedTake(
        unit: Long,
        number: Int,
        verses: IntRange,
        chapter: Int = 1,
        mode: String = "chunk",
        rating: Int = 0,
        timestamp: Long = 0,
        markers: Map<String, Int> = emptyMap()
    ): Long {
        val name = takeName(verses, number, chapter)
        LegacyTakeWav.write(
            old.takeFile("aa", "ulb", "jas", chapter, name), 400, seed = number,
            cues = listOf("${verses.first}" to 0) + markers.toList(),
            metadataJson = LegacyTakeWav.metadataJson(mode, verses.first, verses.last, markers)
        )
        return old.take(unit, number, name, rating, timestamp)
    }

    private fun read(): LegacyReadResult = runBlocking { old.reader().read() }

    private fun singleProject(): LegacyProject = assertIs<LegacyReadResult.Read>(read()).projects.single()
}
