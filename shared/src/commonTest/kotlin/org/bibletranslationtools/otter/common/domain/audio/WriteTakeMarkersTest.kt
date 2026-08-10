package org.bibletranslationtools.otter.common.domain.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.data.audio.BookMarker
import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
import org.bibletranslationtools.otter.common.data.audio.ChunkMarker
import org.bibletranslationtools.otter.common.data.audio.OratureCueType
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.recorder.WavFileWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the marker-write both apps now share. The recorder's copy of this
 * (`PlaybackViewModel.saveVerseMarkers`) had no test at all before this — nothing in
 * :app-recorder referenced PlaybackViewModel — so these tests are the first thing standing
 * between a bad marker write and shipped audio metadata.
 *
 * Engine level: a real WAV on disk, no ViewModel / Koin / UI.
 */
class WriteTakeMarkersTest {

    private val writeTakeMarkers = WriteTakeMarkers()

    @Test
    fun `writes book chapter and verse markers and re-reads them at the frames given`() = runTest {
        val wav = writeTwoSecondWav(this)

        val written = writeTakeMarkers.execute(
            wav,
            listOf(
                BookMarker("gen", 0),
                ChapterMarker(1, 1_000),
                VerseMarker(1, 1, 2_000),
                VerseMarker(2, 2, 30_000)
            ),
            WriteTakeMarkers.ALL_CUE_TYPES
        )

        assertEquals(
            listOf(0, 1_000, 2_000, 30_000),
            written.map { it.location },
            "execute() should return what is on the file, in location order"
        )
        val reread = OratureAudioFile(wav)
        assertEquals(1, reread.getMarker(OratureCueType.BOOK_TITLE).size)
        assertEquals(1, reread.getMarker(OratureCueType.CHAPTER_TITLE).size)
        assertEquals(
            listOf(2_000, 30_000),
            reread.getMarker(OratureCueType.VERSE).map { it.location }.sorted()
        )
    }

    /**
     * The write must replace, not accumulate. Writing a moved marker set has to leave the file
     * with only the new positions — appending would give the take two markers per verse and a
     * waveform full of phantom cues.
     */
    @Test
    fun `a second write replaces the markers from the first`() = runTest {
        val wav = writeTwoSecondWav(this)

        writeTakeMarkers.execute(
            wav,
            listOf(VerseMarker(1, 1, 0), VerseMarker(2, 2, 10_000)),
            WriteTakeMarkers.ALL_CUE_TYPES
        )
        val second = writeTakeMarkers.execute(
            wav,
            listOf(VerseMarker(1, 1, 0), VerseMarker(2, 2, 25_000)),
            WriteTakeMarkers.ALL_CUE_TYPES
        )

        assertEquals(
            listOf(0, 25_000),
            second.map { it.location },
            "the moved marker should replace the old one, not sit alongside it"
        )
    }

    /**
     * Narration's contract: it owns verse/chapter/book cues and must not destroy the CHUNK cues
     * that mark where the chunk boundaries are. This is the behaviour the private
     * `clearNarrationMarkers` in NarrationTakeModifier used to provide.
     */
    @Test
    fun `NARRATION_CUE_TYPES leaves chunk cues on the file`() = runTest {
        val wav = writeTwoSecondWav(this)
        // Seed a chunk cue alongside a verse marker.
        writeTakeMarkers.execute(
            wav,
            listOf(ChunkMarker(1, 500), VerseMarker(1, 1, 1_000)),
            WriteTakeMarkers.ALL_CUE_TYPES
        )
        assertEquals(1, OratureAudioFile(wav).getMarker(OratureCueType.CHUNK).size, "chunk cue seeded")

        // Rewrite only the narration-owned types.
        val written = writeTakeMarkers.execute(
            wav,
            listOf(VerseMarker(1, 1, 40_000)),
            WriteTakeMarkers.NARRATION_CUE_TYPES
        )

        // The return value is the file's markers, not an echo of the argument: the surviving
        // chunk cue was never passed in, so it can only appear here via the re-read. The
        // recorder assigns this straight to its baseMarkers list, so an echo would show the
        // user what we meant to write instead of what is on disk.
        assertEquals(
            listOf(500, 40_000),
            written.map { it.location },
            "execute() should return the file's markers, including cues it did not write"
        )

        val reread = OratureAudioFile(wav)
        assertEquals(
            listOf(500),
            reread.getMarker(OratureCueType.CHUNK).map { it.location },
            "the chunk cue must survive a narration marker write"
        )
        assertEquals(
            listOf(40_000),
            reread.getMarker(OratureCueType.VERSE).map { it.location },
            "the verse marker should have moved"
        )
    }

    /**
     * The counterpart, and the reason [WriteTakeMarkers.replacing] is a parameter rather than a
     * constant: the recorder's save path wipes every cue type. Pinned here so the difference
     * between the two callers is a tested decision rather than an accident.
     */
    @Test
    fun `ALL_CUE_TYPES drops chunk cues`() = runTest {
        val wav = writeTwoSecondWav(this)
        writeTakeMarkers.execute(
            wav,
            listOf(ChunkMarker(1, 500), VerseMarker(1, 1, 1_000)),
            WriteTakeMarkers.ALL_CUE_TYPES
        )

        writeTakeMarkers.execute(
            wav,
            listOf(VerseMarker(1, 1, 40_000)),
            WriteTakeMarkers.ALL_CUE_TYPES
        )

        assertTrue(
            OratureAudioFile(wav).getMarker(OratureCueType.CHUNK).isEmpty(),
            "a full replace should have removed the chunk cue"
        )
    }

    /**
     * Callers pass markers in whatever order they hold them; the returned list is ordered by
     * location because consumers index into it positionally to label waveform cues.
     */
    @Test
    fun `returns markers in location order regardless of input order`() = runTest {
        val wav = writeTwoSecondWav(this)

        val written = writeTakeMarkers.execute(
            wav,
            listOf(
                VerseMarker(3, 3, 30_000),
                VerseMarker(1, 1, 1_000),
                VerseMarker(2, 2, 20_000)
            ),
            WriteTakeMarkers.ALL_CUE_TYPES
        )

        assertEquals(listOf(1_000, 20_000, 30_000), written.map { it.location })
    }

    @Test
    fun `writing an empty marker list clears the file`() = runTest {
        val wav = writeTwoSecondWav(this)
        writeTakeMarkers.execute(
            wav,
            listOf(VerseMarker(1, 1, 0), VerseMarker(2, 2, 10_000)),
            WriteTakeMarkers.ALL_CUE_TYPES
        )

        val written = writeTakeMarkers.execute(wav, emptyList(), WriteTakeMarkers.ALL_CUE_TYPES)

        assertTrue(written.isEmpty(), "clearing then writing nothing should leave no markers")
        assertTrue(OratureAudioFile(wav).getMarkers().isEmpty())
    }

    /** Creates a finalized ~2s mono 44.1k/16-bit WAV (mirrors MarkerPlacementModelRoundTripTest). */
    private suspend fun writeTwoSecondWav(scope: CoroutineScope): File {
        val wav = File.createTempFile("write-take-markers", ".wav").apply { deleteOnExit() }
        val stream = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        val writer = WavFileWriter(
            oratureAudioFile = OratureAudioFile(wav, 1, 44_100, 16),
            audioStream = stream,
            append = false,
            onComplete = {},
            scope = scope
        )
        writer.listen()
        writer.start()
        val sampleRate = 44_100
        val totalSamples = sampleRate * 2
        var emitted = 0
        while (emitted < totalSamples) {
            val count = minOf(441, totalSamples - emitted)
            stream.emit(ByteArray(count * 2))
            emitted += count
        }
        delay(100)
        writer.pause()
        writer.closeAndJoin()
        assertTrue(OratureAudioFile(wav).totalFrames > 44_100, "WAV should hold ~2s of frames")
        return wav
    }
}
