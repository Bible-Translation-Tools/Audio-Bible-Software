package org.bibletranslationtools.shared.audio.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioTransportHarness
import org.bibletranslationtools.otter.common.device.withBufferedTransport
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the drawn playhead still matches **what you can hear** after the transport has been worked.
 *
 * Everything else about the display asks whether its position is smooth or monotonic, which it can be
 * while pointing at the wrong frame entirely. This asks the only question a listener asks: is the audio
 * coming out of the speaker the audio the playhead is sitting on?
 *
 * Answering it needs a sink with a real hardware queue, because the gap between "written" and "audible"
 * is where a pause loses track: `pause()` flushes, and flushing DISCARDS up to a bufferful of audio that
 * was already read from the file and handed to the hardware but never heard. Whether the take resumes
 * from the audible frame or from the write cursor decides whether that stretch is replayed or skipped —
 * and a mock with no queue cannot tell the two apart. See
 * [org.bibletranslationtools.otter.common.device.BufferedAudioSink].
 */
class PlaybackDisplayDriftTest {

    /**
     * The reported symptom: pause and play repeatedly, then let it run, and the audio is well ahead of
     * the waveform.
     */
    @Test
    fun theDrawnPositionStillMatchesTheAudibleFrameAfterManyPauseResumeCycles() = runTest {
        withBufferedTransport(takeFrames = SAMPLE_RATE * 20, bufferFrames = BUFFER_FRAMES) {
            val connection = connection()
            val host = DisplayHost(this, connection)

            host.play()
            host.drive(300)

            repeat(CYCLES) {
                host.pause()
                host.drive(40)
                host.play()
                host.drive(120)
            }

            // Then just let it play, long enough for any correction to converge.
            host.drive(1_500)

            val audible = bufferedSink.audibleContentFrame
            val drawn = host.displayFrame()
            val driftFrames = audible - drawn
            val driftMillis = driftFrames * 1_000 / SAMPLE_RATE

            assertTrue(
                audible > SAMPLE_RATE / 2,
                "the take must actually have played for this to mean anything (audible frame $audible)"
            )
            assertTrue(
                abs(driftMillis) < TOLERANCE_MILLIS,
                "after $CYCLES pause/resume cycles the audio is ${driftMillis}ms " +
                    "${if (driftFrames > 0) "AHEAD of" else "behind"} the drawn playhead " +
                    "(audible frame $audible, drawn $drawn)"
            )
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BUFFER_FRAMES = 44_100 * 4 / 10 // ~400ms, the measured macOS queue depth
        const val CYCLES = 8

        /** The slew band: inside this the clock is correcting invisibly rather than being wrong. */
        const val TOLERANCE_MILLIS = 250
    }
}

/**
 * A stand-in for `PlaybackViewModel`, doing what it does in the order it does it: freeze the clock at the
 * DISPLAYED frame on pause (never polling the player, which reports the write cursor once the sink
 * stops), and `startAdvancing` on play.
 */
private class DisplayHost(
    private val harness: AudioTransportHarness,
    private val connection: AudioPlayerConnection
) {
    private val clock = PlaybackDisplayPosition(
        positionSource = { connection.getLocationInFrames().toLong() },
        positionReliable = { connection.isPositionReliable() }
    ).apply {
        sampleRate = 44_100
        durationFrames = harness.takeFrames.toLong()
    }

    private var loaded = false

    fun displayFrame(): Long = clock.displayFrame

    suspend fun play() {
        if (!loaded) {
            connection.load(harness.take)
            harness.awaitEvent("Load")
            loaded = true
        }
        connection.play()
        clock.startAdvancing()
    }

    suspend fun pause() {
        val displayed = clock.displayFrame
        clock.advancing = false
        connection.pause()
        clock.snapTo(displayed)
        harness.awaitEvent("Pause")
    }

    /** Runs the display clock for [millis] of real time at ~60 Hz. */
    suspend fun drive(millis: Long) {
        repeat((millis / FRAME_MILLIS).toInt()) {
            delay(FRAME_MILLIS)
            clock.onFrame(System.nanoTime())
        }
    }

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}
