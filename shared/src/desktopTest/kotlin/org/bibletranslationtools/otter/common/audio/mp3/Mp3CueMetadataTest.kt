package org.bibletranslationtools.otter.common.audio.mp3

import org.bibletranslationtools.otter.common.audio.wav.WavFile
import org.bibletranslationtools.otter.common.domain.audio.AudioConverter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cue sheets whose optional TITLE fields are absent.
 *
 * cuelib is Java, so `CueSheet.getTitle()` and `TrackData.getTitle()` are platform types that return
 * null when the field is not present — and TITLE is optional, which the cue files written beside
 * exported chapter audio take advantage of. Reading `cuesheet.title` into a non-null Kotlin String
 * therefore threw before a single track was parsed, and because the whole parse sits inside a
 * `catch (e: Exception)` that only logs, the result was source audio with NO verse markers at all
 * and one "Error in initializing Mp3 Metadata" line in the log to explain it.
 *
 * The mp3 is encoded here with the app's own converter rather than checked in, so the fixture is a
 * real file that mp3agic will actually parse.
 */
class Mp3CueMetadataTest {

    private fun fixture(cueBody: String): Pair<File, File> {
        val dir = kotlin.io.path.createTempDirectory("mp3cue").toFile().apply { deleteOnExit() }
        val wav = File(dir, "tone.wav")
        // A second of silence is enough: this is about the cue sheet, not the audio.
        WavFile(wav, 1, 44_100, 16).writer().use { out ->
            out.write(ByteArray(44_100 * 2))
        }
        val mp3 = File(dir, "tone.mp3")
        AudioConverter().wavToMp3(wav, mp3).blockingAwait()
        assertTrue(mp3.exists() && mp3.length() > 0, "the fixture mp3 should have been encoded")

        val cue = File(dir, "tone.cue").apply { writeText(cueBody) }
        return mp3 to cue
    }

    @Test
    fun `a cue sheet with no TITLE anywhere still yields its markers`() {
        // Exactly the shape Orature writes: no sheet TITLE, no track TITLE.
        val (mp3, cue) = fixture(
            """
            FILE "tone.mp3" MP3
              TRACK 01 AUDIO
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                INDEX 01 00:00:02
            """.trimIndent()
        )

        val cues = Mp3Metadata(mp3, cue).getCues()

        // Was empty: the sheet-level TITLE threw before any track was read.
        assertEquals(2, cues.size, "both tracks should have produced a cue")
        // A track with no TITLE is labelled by its own number, which is what the marker parsers
        // read as a verse number.
        assertEquals(listOf("1", "2"), cues.map { it.label })
        assertEquals(0, cues.first().location, "the first index is at the start of the file")
    }

    @Test
    fun `a TITLE is still used when the cue sheet has one`() {
        val (mp3, cue) = fixture(
            """
            TITLE "Jonah 1"
            FILE "tone.mp3" MP3
              TRACK 01 AUDIO
                TITLE "3"
                INDEX 01 00:00:00
            """.trimIndent()
        )

        val cues = Mp3Metadata(mp3, cue).getCues()

        assertEquals(1, cues.size)
        assertEquals("3", cues.single().label, "an explicit track title still wins over the number")
    }
}
