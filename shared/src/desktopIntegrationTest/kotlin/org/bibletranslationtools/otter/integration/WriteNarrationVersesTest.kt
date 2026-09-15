package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.audio.wav.WavFile
import org.bibletranslationtools.otter.common.data.audio.BookMarker
import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.AudioBouncer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.narration.AudioFileUtils
import org.bibletranslationtools.otter.common.domain.narration.ExtractNarrationVerses
import org.bibletranslationtools.otter.common.domain.narration.WriteNarrationVerses
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Writing a chapter's narration from one audio file per unit.
 *
 * Most of these read the result back through [ExtractNarrationVerses] rather than inspecting the
 * files, which checks the two halves against each other rather than against a second transcription
 * of the format. Should the writer and the reader ever disagree — about region units, ordering, or
 * which marker identifies a unit — a round trip stops returning what went in.
 */
class WriteNarrationVersesTest {

    private var env: IntegrationEnvironment? = null
    private lateinit var write: WriteNarrationVerses
    private lateinit var extract: ExtractNarrationVerses

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    /** A verse-by-verse Jude project: 25 verse units plus the book and chapter titles. */
    private fun judeProject(): Triple<IntegrationEnvironment, Workbook, Chapter> {
        val e = IntegrationEnvironment.create().also { env = it }
        write = WriteNarrationVerses(AudioFileUtils(e.directoryProvider))
        extract = ExtractNarrationVerses(e.directoryProvider, AudioBouncer())
        e.import("en_ulb.zip")
        val derived = e.createProject(
            sourceProject = e.sourceBook(BOOK),
            targetLanguage = e.language("en"),
            mode = ProjectMode.DIALECT,
            deriveProjectFromVerses = true
        )
        val workbook = e.workbook(derived)
        val chapter = workbook.target.chapters.toList().blockingGet().single()
        return Triple(e, workbook, chapter)
    }

    /**
     * A take whose every sample carries [id], so the audio a round trip returns can be traced to the
     * unit it was written for rather than merely counted.
     */
    private fun take(id: Int, frames: Int): File {
        val file = File.createTempFile("take-$id-", ".wav").apply { deleteOnExit() }
        WavFile(file, 1, 44100, 16).let { wav ->
            wav.writer().use { it.write(ByteArray(frames * 2) { id.toByte() }) }
        }
        return file
    }

    private fun verseUnit(verse: Int, frames: Int) =
        WriteNarrationVerses.Unit(VerseMarker(verse, verse, 0), take(verse, frames))

    /** The first sample of a section's audio, which identifies the take it came from. */
    private fun firstSample(file: File): Int {
        val audio = OratureAudioFile(file)
        val buffer = ByteArray(2)
        audio.reader().use { reader ->
            reader.open()
            reader.getPcmBuffer(buffer)
        }
        return buffer[0].toInt()
    }

    private fun chapterDir(workbook: Workbook, chapter: Chapter) =
        workbook.projectFilesAccessor.getChapterAudioDir(workbook, chapter)

    @Test
    fun `what is written comes back unit for unit`() {
        val (_, workbook, chapter) = judeProject()
        val units = listOf(verseUnit(1, 500), verseUnit(2, 700), verseUnit(3, 300))

        assertTrue(write.execute(workbook, chapter, units))

        val sections = extract.execute(workbook, chapter)
        assertEquals(listOf("1", "2", "3"), sections.map { it.marker.label })
        assertEquals(listOf(500, 700, 300), sections.map { OratureAudioFile(it.audio).totalFrames })
        // Each unit's audio is its own, not a neighbour's — which is what region arithmetic gets wrong.
        assertEquals(listOf(1, 2, 3), sections.map { firstSample(it.audio) })
    }

    @Test
    fun `a chapter recorded in part writes only the units it has`() {
        // The case a compiled chapter take cannot express, since compiling needs every unit.
        val (_, workbook, chapter) = judeProject()

        assertTrue(write.execute(workbook, chapter, listOf(verseUnit(2, 400), verseUnit(7, 400))))

        val sections = extract.execute(workbook, chapter)
        assertEquals(listOf("2", "7"), sections.map { it.marker.label })
        assertEquals(listOf(2, 7), sections.map { firstSample(it.audio) })
    }

    @Test
    fun `titles are written ahead of the verses, in the order given`() {
        val (_, workbook, chapter) = judeProject()
        val units = listOf(
            WriteNarrationVerses.Unit(BookMarker(BOOK, 0), take(9, 200)),
            WriteNarrationVerses.Unit(ChapterMarker(1, 0), take(8, 200)),
            verseUnit(1, 400)
        )

        assertTrue(write.execute(workbook, chapter, units))

        val sections = extract.execute(workbook, chapter)
        assertEquals(3, sections.size, "titles must not be dropped: ${sections.map { it.marker.label }}")
        assertContentEquals(listOf(9, 8, 1), sections.map { firstSample(it.audio) })
    }

    @Test
    fun `regions are byte offsets and locations are frame offsets`() {
        // The one place the format mixes units. Getting it wrong puts a marker at several times its
        // real position, which reads as audio the unit does not contain.
        val (_, workbook, chapter) = judeProject()
        write.execute(workbook, chapter, listOf(verseUnit(1, 500), verseUnit(2, 500)))

        val map = File(chapterDir(workbook, chapter), "active_verses.json").readText()
        val scratch = File(chapterDir(workbook, chapter), "chapter_narration.pcm")

        assertEquals(2000, scratch.length(), "two 500-frame units at 2 bytes a frame")
        // Verse 2 starts halfway: byte 1000, which is frame 500.
        assertTrue(map.contains("\"start\":1000"), "region start in bytes; got $map")
        assertTrue(map.contains("\"location\":500"), "marker location in frames; got $map")
    }

    @Test
    fun `writing again replaces what was there`() {
        // Appending to an existing scratch recording would leave the earlier audio in place with no
        // region pointing at it, growing the file on every export.
        val (_, workbook, chapter) = judeProject()
        write.execute(workbook, chapter, listOf(verseUnit(1, 500), verseUnit(2, 500)))
        val first = File(chapterDir(workbook, chapter), "chapter_narration.pcm").length()

        write.execute(workbook, chapter, listOf(verseUnit(3, 500)))

        val scratch = File(chapterDir(workbook, chapter), "chapter_narration.pcm")
        assertEquals(1000, scratch.length(), "only the second write's audio; first was $first")
        val sections = extract.execute(workbook, chapter)
        assertEquals(listOf("3"), sections.map { it.marker.label })
        assertEquals(listOf(3), sections.map { firstSample(it.audio) })
    }

    @Test
    fun `no units leaves no narration behind`() {
        val (_, workbook, chapter) = judeProject()
        write.execute(workbook, chapter, listOf(verseUnit(1, 500)))
        assertTrue(extract.hasNarration(workbook, chapter))

        assertFalse(write.execute(workbook, chapter, emptyList()))

        assertFalse(
            extract.hasNarration(workbook, chapter),
            "a verse map with no entries would claim the chapter has narration and show nothing"
        )
    }

    @Test
    fun `an unreadable unit is skipped without shifting the others`() {
        val (_, workbook, chapter) = judeProject()
        val broken = File.createTempFile("broken-", ".wav").apply {
            writeText("not audio")
            deleteOnExit()
        }
        val units = listOf(
            verseUnit(1, 500),
            WriteNarrationVerses.Unit(VerseMarker(2, 2, 0), broken),
            verseUnit(3, 500)
        )

        assertTrue(write.execute(workbook, chapter, units))

        val sections = extract.execute(workbook, chapter)
        assertEquals(listOf("1", "3"), sections.map { it.marker.label }, "verse 2 had no audio")
        // Verse 3 still returns its own audio rather than being read from the wrong offset.
        assertEquals(listOf(1, 3), sections.map { firstSample(it.audio) })
        assertEquals(listOf(500, 500), sections.map { OratureAudioFile(it.audio).totalFrames })
    }

    @Test
    fun `deleting narration removes both files`() {
        val (_, workbook, chapter) = judeProject()
        write.execute(workbook, chapter, listOf(verseUnit(1, 500)))

        assertTrue(write.deleteNarration(workbook, chapter))

        assertFalse(File(chapterDir(workbook, chapter), "chapter_narration.pcm").exists())
        assertFalse(File(chapterDir(workbook, chapter), "active_verses.json").exists())
        assertTrue(write.deleteNarration(workbook, chapter), "deleting twice is not an error")
    }

    private companion object {
        /** One chapter, 25 verses. */
        const val BOOK = "jud"
    }
}
