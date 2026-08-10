package org.bibletranslationtools.otter.common.device

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The reported position must advance with the audio, on every device — including one whose
 * `available()` cannot be trusted.
 *
 * `framePosition` used to be `writtenFrames - (bufferSize - available())`. That is only the audible
 * position if `available()` tracks the drain, and measured on a real machine it does not: with a deep
 * device buffer the two terms cancel while the buffer fills, so the position read **0 for three seconds
 * of audible playback** and then leapt to catch up. From the field logs, with audio playing the whole
 * time:
 *
 * ```
 * display=41331   source=0
 * display=61250   source=0
 * display=96493   source=11827
 * display=141357  source=57432
 * display=101910  source=102096   <- the drawn playhead yanked back 39,447 frames
 * ```
 *
 * Everything downstream followed from those seconds: the display free-ran (correctly — it cannot correct
 * toward a source it is told to distrust) and was then hard-snapped backwards, and `pause()` recorded a
 * resume position from the same garbage, so toggling the transport skipped or replayed seconds of the
 * take. Once the source became truthful the clock tracked it to within ±34 ms, which is the tell: the
 * clock was never the problem.
 *
 * Audio advances at exactly one frame per 1/sampleRate of real time. That is the ground truth here, it
 * needs no cooperation from the device, and it is what the position is now derived from — clamped to what
 * has actually been written, so it can never claim to have played audio that was never handed over.
 */
class JvmAudioSinkPositionTest {

    private val spec = AudioSpec(sampleRate = 44_100, bitDepth = 16, channels = 1)

    /** Three seconds of 16-bit mono, the depth that made the field symptom last three seconds. */
    private val DEEP_BUFFER_BYTES = 44_100 * 3 * 2

    /** One buffer of 16-bit mono: 1024 frames. */
    private fun bufferOf(frames: Int) = ByteArray(frames * spec.bytesPerFrame)

    @Test
    fun thePositionAdvancesWhileTheDeviceClaimsItsBufferIsAlwaysFull() {
        // available() == 0 forever: the pathological device. `bufferSize - available()` then reports the
        // whole buffer as permanently queued, and the old formula pinned the position at zero.
        // A ~3 second device buffer, matching the field logs where the position stayed at 0 for three
        // seconds. A shallow buffer hides this: the bug is the SIZE of the bogus offset.
        val sink = JvmAudioSink { FakeSourceDataLine(availableOverride = 0, bufferBytes = DEEP_BUFFER_BYTES) }
        sink.open(spec)
        sink.start()

        // Hand over a second of audio, as the playback loop would.
        repeat(44) { sink.write(bufferOf(1_024), 0, 1_024 * spec.bytesPerFrame) }
        Thread.sleep(250)

        val position = sink.framePosition
        assertTrue(
            position > spec.sampleRate / 10,
            "after 250ms of playing a device that misreports available(), the position was $position " +
                "frames — the playhead is pinned near zero while audio plays, which is what left the " +
                "display free-running for seconds and then snapping backwards"
        )
        assertTrue(
            position <= 44L * 1_024,
            "the position must never exceed what has been written ($position of ${44 * 1_024} frames): " +
                "claiming to have played audio the hardware was never given is how a pause records a " +
                "resume point past what anyone heard, and swallows the difference"
        )
    }

    @Test
    fun thePositionNeverRunsAheadOfWhatHasBeenWritten() {
        val sink = JvmAudioSink { FakeSourceDataLine(availableOverride = 0, bufferBytes = DEEP_BUFFER_BYTES) }
        sink.open(spec)
        sink.start()

        // Only a little audio handed over, but plenty of wall time passing.
        sink.write(bufferOf(1_024), 0, 1_024 * spec.bytesPerFrame)
        Thread.sleep(300)

        assertTrue(
            sink.framePosition <= 1_024L,
            "only 1024 frames were written, so at most 1024 frames can have been heard, however much " +
                "time has passed (was ${sink.framePosition})"
        )
    }

    @Test
    fun thePositionHoldsStillWhileStopped() {
        val sink = JvmAudioSink { FakeSourceDataLine() }
        sink.open(spec)
        sink.start()
        repeat(20) { sink.write(bufferOf(1_024), 0, 1_024 * spec.bytesPerFrame) }
        Thread.sleep(120)

        sink.stop()
        val atStop = sink.framePosition
        Thread.sleep(150)

        assertTrue(
            sink.framePosition == atStop,
            "a stopped line plays nothing, so its position must not creep with the wall clock " +
                "($atStop then ${sink.framePosition})"
        )
    }

    @Test
    fun flushResetsThePositionToZero() {
        val sink = JvmAudioSink { FakeSourceDataLine() }
        sink.open(spec)
        sink.start()
        repeat(20) { sink.write(bufferOf(1_024), 0, 1_024 * spec.bytesPerFrame) }
        Thread.sleep(80)

        sink.flush()

        assertTrue(
            sink.framePosition == 0L,
            "AudioBufferPlayer re-anchors on the next play/seek and expects the counter to restart at 0 " +
                "after a flush (was ${sink.framePosition})"
        )
    }

    @Test
    fun thePositionResumesFromWhereItStoppedRatherThanRestarting() {
        val sink = JvmAudioSink { FakeSourceDataLine() }
        sink.open(spec)
        sink.start()
        repeat(40) { sink.write(bufferOf(1_024), 0, 1_024 * spec.bytesPerFrame) }
        Thread.sleep(120)
        sink.stop()
        val atStop = sink.framePosition

        // A stop that is NOT followed by a flush: the queued audio is still there and resumes.
        sink.start()
        Thread.sleep(100)

        assertTrue(
            sink.framePosition >= atStop,
            "resuming without a flush continues from where it stopped, never backwards " +
                "($atStop then ${sink.framePosition})"
        )
    }
}
