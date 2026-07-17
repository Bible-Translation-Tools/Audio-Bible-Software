package org.bibletranslationtools.otter.common.domain.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.data.audio.OratureCueType
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.recorder.WavFileWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the correctness-critical path of the built-in Verse Marker editor (Phase 14): placing and
 * moving verse markers through [MarkerPlacementModel], writing them into the take via
 * [MarkerPlacementModel.writeMarkers], and re-reading them from a fresh [OratureAudioFile] — i.e. the
 * save→reopen round-trip that narration relies on after "Edit Verse Markers".
 *
 * This is engine-level (no ViewModel / Koin / UI), so it exercises exactly what the editor persists.
 */
class MarkerPlacementModelRoundTripTest {

    @Test
    fun placedMarkersPersistAndReReadAtCorrectFrames() = runTest {
        val wav = writeTwoSecondWav(this)

        // Reserved set = two verse markers, initially unplaced (the fresh take has no cues).
        val reserved = listOf(VerseMarker(1, 1, 0), VerseMarker(2, 2, 0))
        val model = MarkerPlacementModel(MarkerPlacementType.VERSE, OratureAudioFile(wav), reserved)

        // Place verse 1 at frame 0 and verse 2 at frame 44100 (addMarker places the next unplaced
        // reserved marker at the playhead — the editor's placeMarker).
        model.addMarker(0)
        model.addMarker(44_100)
        assertEquals(2, model.markerItems.count { it.placed }, "both verse markers should be placed")

        model.writeMarkers().blockingAwait()

        // Re-read from a fresh handle: the two verse cues must survive at the frames we placed them.
        val reread = OratureAudioFile(wav).getMarker(OratureCueType.VERSE).sortedBy { it.location }
        assertEquals(2, reread.size, "expected two persisted verse markers")
        assertEquals(0, reread[0].location)
        assertEquals(44_100, reread[1].location)
    }

    @Test
    fun movingAMarkerPersistsTheNewFrame() = runTest {
        val wav = writeTwoSecondWav(this)
        val reserved = listOf(VerseMarker(1, 1, 0), VerseMarker(2, 2, 0))
        val model = MarkerPlacementModel(MarkerPlacementType.VERSE, OratureAudioFile(wav), reserved)

        model.addMarker(0)
        val second = model.addMarker(44_100)
        assertTrue(second >= 0, "second marker should have a valid id")

        // Move the second marker to a new frame and persist (the editor's moveMarker → writeMarkers).
        model.moveMarker(second, 44_100, 60_000)
        model.writeMarkers().blockingAwait()

        val reread = OratureAudioFile(wav).getMarker(OratureCueType.VERSE).sortedBy { it.location }
        assertEquals(listOf(0, 60_000), reread.map { it.location }, "moved marker frame should persist")
    }

    /** Creates a finalized ~2s mono 44.1k/16-bit WAV (mirrors WavFileWriterTest's harness). */
    private suspend fun writeTwoSecondWav(scope: CoroutineScope): File {
        val wav = File.createTempFile("marker-roundtrip", ".wav").apply { deleteOnExit() }
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
            stream.emit(ByteArray(count * 2)) // silence is fine; only the frame count matters here
            emitted += count
        }
        delay(100)
        writer.pause()
        writer.closeAndJoin()
        assertTrue(OratureAudioFile(wav).totalFrames > 44_100, "WAV should hold ~2s of frames")
        return wav
    }
}
