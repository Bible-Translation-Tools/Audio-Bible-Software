package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.AudioBouncer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.narration.ExtractNarrationVerses
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Turning a chapter's narration into one audio file per unit, against a real project on a real
 * database.
 *
 * Narration stores a chapter as one scratch recording plus a map of which byte regions belong to
 * which unit. The fixtures here write that pair directly, which is what an imported Orature backup
 * leaves in the project, and pin two properties the conversion depends on:
 *
 *  - a unit's audio comes back exactly, so a take made from it holds what was recorded;
 *  - a unit whose audio was re-recorded owns several regions that need not be adjacent or in order,
 *    and those are stitched back together in region order. Slicing the scratch file on unit
 *    boundaries would silently return the wrong audio for such a unit.
 */
class ExtractNarrationVersesTest {

    private var env: IntegrationEnvironment? = null
    private lateinit var extract: ExtractNarrationVerses

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    /** A verse-by-verse Jude project: 25 verse units plus the book and chapter titles. */
    private fun judeProject(): Triple<IntegrationEnvironment, Workbook, Chapter> {
        val e = IntegrationEnvironment.create().also { env = it }
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

    private fun chapterDir(workbook: Workbook, chapter: Chapter): File =
        workbook.projectFilesAccessor.getChapterAudioDir(workbook, chapter)

    /**
     * Writes a scratch recording whose every frame encodes its own index, so any region read back can
     * be checked against the region that was asked for rather than merely by length.
     */
    private fun writeScratch(dir: File, frames: Int): File {
        val pcm = File(dir, "chapter_narration.pcm")
        pcm.parentFile.mkdirs()
        val bytes = ByteArray(frames * 2)
        for (frame in 0 until frames) {
            bytes[frame * 2] = (frame and 0xFF).toByte()
            bytes[frame * 2 + 1] = ((frame shr 8) and 0xFF).toByte()
        }
        pcm.writeBytes(bytes)
        return pcm
    }

    /** `sectors` are byte offsets into the scratch recording; `location` is in frames. */
    private fun verseNode(verse: Int, sectors: List<IntRange>): String {
        val ranges = sectors.joinToString(",") { """{"start":${it.first},"end":${it.last}}""" }
        val location = sectors.first().first / 2
        return """
            {"placed":true,
             "marker":{"marker_type":"verse_marker","start":$verse,"end":$verse,
                       "location":$location,"content_type":"CONTENT"},
             "sectors":[$ranges]}
        """.trimIndent()
    }

    private fun writeVerseMap(dir: File, nodes: List<String>) {
        File(dir, "active_verses.json").writeText(nodes.joinToString(",", "[", "]"))
    }

    /** The frame indices encoded in [wav]'s samples, which identify the regions it was read from. */
    private fun framesOf(wav: File): List<Int> {
        val audio = OratureAudioFile(wav)
        val bytes = ByteArray(audio.totalFrames * 2)
        audio.reader().use { reader ->
            reader.open()
            var offset = 0
            val buffer = ByteArray(8192)
            while (reader.hasRemaining() && offset < bytes.size) {
                val read = reader.getPcmBuffer(buffer)
                if (read <= 0) break
                System.arraycopy(buffer, 0, bytes, offset, minOf(read, bytes.size - offset))
                offset += read
            }
        }
        return (0 until bytes.size / 2).map { frame ->
            (bytes[frame * 2].toInt() and 0xFF) or ((bytes[frame * 2 + 1].toInt() and 0xFF) shl 8)
        }
    }

    @Test
    fun `each recorded unit comes back as its own audio file`() {
        val (_, workbook, chapter) = judeProject()
        val dir = chapterDir(workbook, chapter)
        writeScratch(dir, frames = 3000)
        // Verses 1, 2 and 3 hold 500 frames each, laid down back to back.
        writeVerseMap(
            dir,
            listOf(
                verseNode(1, listOf(0..999)),
                verseNode(2, listOf(1000..1999)),
                verseNode(3, listOf(2000..2999))
            )
        )

        val sections = extract.execute(workbook, chapter)

        assertEquals(listOf("1", "2", "3"), sections.map { it.marker.label })
        assertEquals(listOf(500, 500, 500), sections.map { OratureAudioFile(it.audio).totalFrames })
        // Each file holds the frames of its own region, not merely the right number of them.
        assertEquals(0..499, framesOf(sections[0].audio).let { it.first()..it.last() })
        assertEquals(500..999, framesOf(sections[1].audio).let { it.first()..it.last() })
        assertEquals(1000..1499, framesOf(sections[2].audio).let { it.first()..it.last() })
    }

    @Test
    fun `a re-recorded unit is stitched from all of its regions`() {
        val (_, workbook, chapter) = judeProject()
        val dir = chapterDir(workbook, chapter)
        writeScratch(dir, frames = 3000)
        // Verse 1 owns two regions that are neither adjacent nor in scratch order: the region at
        // frames 1000-1099 was recorded after the one at 0-99, which is what re-recording leaves.
        writeVerseMap(
            dir,
            listOf(
                verseNode(1, listOf(2000..2199, 0..199)),
                verseNode(2, listOf(400..599))
            )
        )

        val sections = extract.execute(workbook, chapter)

        assertEquals(listOf("1", "2"), sections.map { it.marker.label })
        assertEquals(200, OratureAudioFile(sections[0].audio).totalFrames, "both regions, 100 + 100")
        // Region order, not scratch order: the later-recorded region comes first.
        assertContentEquals(
            (1000..1099).toList() + (0..99).toList(),
            framesOf(sections[0].audio),
            "a re-recorded unit must be stitched in region order"
        )
        assertContentEquals((200..299).toList(), framesOf(sections[1].audio))
    }

    @Test
    fun `book and chapter titles are extracted alongside the verses`() {
        val (_, workbook, chapter) = judeProject()
        val dir = chapterDir(workbook, chapter)
        writeScratch(dir, frames = 2000)
        writeVerseMap(
            dir,
            listOf(
                """{"placed":true,"marker":{"marker_type":"book_marker","bookSlug":"$BOOK",
                   "location":0,"content_type":"TITLE"},"sectors":[{"start":0,"end":199}]}""",
                """{"placed":true,"marker":{"marker_type":"chapter_marker","chapterNumber":1,
                   "location":100,"content_type":"TITLE"},"sectors":[{"start":200,"end":399}]}""",
                verseNode(1, listOf(400..599))
            )
        )

        val sections = extract.execute(workbook, chapter)

        assertEquals(3, sections.size, "titles must not be dropped: ${sections.map { it.marker.label }}")
        assertEquals(100, OratureAudioFile(sections[0].audio).totalFrames)
        assertEquals(100, OratureAudioFile(sections[2].audio).totalFrames)
    }

    @Test
    fun `a section's marker sits at the start of its own audio`() {
        // The narration marker records where the unit sat within the chapter. A section is a
        // standalone file, so carrying that offset over puts the marker past the end of the audio —
        // a take written from it would claim a position it does not contain.
        val (_, workbook, chapter) = judeProject()
        val dir = chapterDir(workbook, chapter)
        writeScratch(dir, frames = 3000)
        writeVerseMap(
            dir,
            listOf(
                verseNode(1, listOf(0..999)),
                // Verse 2 begins 500 frames into the chapter, so its narration marker says 500.
                verseNode(2, listOf(1000..1999))
            )
        )

        val sections = extract.execute(workbook, chapter)

        sections.forEach { section ->
            assertEquals(
                0,
                section.marker.location,
                "${section.marker.label} must be positioned within its own file"
            )
            assertTrue(
                section.marker.location < OratureAudioFile(section.audio).totalFrames,
                "${section.marker.label} is beyond the end of its audio"
            )
        }
        // The identity of the unit still survives the repositioning.
        assertEquals(listOf("1", "2"), sections.map { it.marker.label })
    }

    @Test
    fun `an unrecorded unit is absent rather than empty`() {
        val (_, workbook, chapter) = judeProject()
        val dir = chapterDir(workbook, chapter)
        writeScratch(dir, frames = 2000)
        // Only verse 2 was recorded.
        writeVerseMap(dir, listOf(verseNode(2, listOf(0..399))))

        val sections = extract.execute(workbook, chapter)

        assertEquals(listOf("2"), sections.map { it.marker.label })
    }

    @Test
    fun `units the project does not have are not placed`() {
        // The label guard the caller depends on: a narration whose units do not match the project's
        // yields nothing, so a caller about to delete the narration must not read that as
        // "nothing was recorded". Jude has 25 verses, so verse 90 belongs to no unit here.
        val (_, workbook, chapter) = judeProject()
        val dir = chapterDir(workbook, chapter)
        writeScratch(dir, frames = 2000)
        writeVerseMap(dir, listOf(verseNode(90, listOf(0..399))))

        assertTrue(extract.hasNarration(workbook, chapter), "the narration files are present")
        assertTrue(extract.execute(workbook, chapter).isEmpty(), "nothing places")
    }

    @Test
    fun `narration presence is reported from the files on disk`() {
        val (_, workbook, chapter) = judeProject()
        val dir = chapterDir(workbook, chapter)

        assertFalse(extract.hasNarration(workbook, chapter), "nothing written yet")

        writeScratch(dir, frames = 1000)
        writeVerseMap(dir, listOf(verseNode(1, listOf(0..399))))
        assertTrue(extract.hasNarration(workbook, chapter))

        assertTrue(extract.deleteNarration(workbook, chapter))
        assertFalse(extract.hasNarration(workbook, chapter))
        assertFalse(File(dir, "chapter_narration.pcm").exists())
        assertFalse(File(dir, "active_verses.json").exists())
        assertTrue(extract.deleteNarration(workbook, chapter), "deleting twice is not an error")
    }

    private companion object {
        /** One chapter, 25 verses. */
        const val BOOK = "jud"
    }
}
