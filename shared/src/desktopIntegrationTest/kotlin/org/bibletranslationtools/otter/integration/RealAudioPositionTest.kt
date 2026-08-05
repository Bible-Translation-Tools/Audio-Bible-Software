package org.bibletranslationtools.otter.integration

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.device.AudioFileReader
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.bibletranslationtools.otter.common.device.DefaultAudioProcessor
import org.bibletranslationtools.otter.common.device.JvmAudioSink
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the player reports about its position while playing through **real audio hardware**.
 *
 * ### Why this is split in two
 *
 * The first version of this measured one number — position advance versus wall time across pause/resume
 * cycles — and it was **bimodal**: 248ms, 1617ms, 164ms on consecutive runs at identical code. Two
 * decisions got made on single samples from it before the spread was noticed.
 *
 * The cause was in the accounting, not the audio. It counted wall time from `play()` onwards as "heard",
 * so however long playback took to actually start was charged to position tracking. Startup latency turns
 * out to vary from a few milliseconds to hundreds, and six cycles of that is the whole 1500ms swing.
 *
 * So the two are now measured separately, and neither test waits on a guess:
 *  - [theStartupLatencyOfEachResumeIsMeasured] reports the startup distribution as the subject in its own
 *    right, because that variance is a real defect and the number worth chasing.
 *  - the tracking tests below start their clocks only once the position is demonstrably moving, so what
 *    they report is position accuracy and nothing else.
 *
 * Ground truth throughout is the wall clock: audio plays at exactly one frame per 1/sampleRate of real
 * time, whatever the buffer is doing.
 *
 * In the integration tier because it needs an output device and takes real seconds. Skips rather than
 * fails when no line is available.
 */
class RealAudioPositionTest {

    private val spec = AudioSpec(sampleRate = 44_100, bitDepth = 16, channels = 1)

    @Test
    fun theReportedPositionTracksElapsedTimeWhilePlaying() = withPlayer { connection ->
        connection.play()
        val startupMillis = awaitPlaybackStart(connection)

        val startPosition = connection.getLocationInFrames().toLong()
        val startNanos = System.nanoTime()
        delay(3_000)
        val endPosition = connection.getLocationInFrames().toLong()
        val elapsedNanos = System.nanoTime() - startNanos

        val expected = elapsedNanos * spec.sampleRate / 1_000_000_000L
        val reported = endPosition - startPosition
        val errorMillis = (reported - expected) * 1_000 / spec.sampleRate
        println(
            "[AUDIO] steady play: reported=$reported expected=$expected error=${errorMillis}ms " +
                "(startup=${startupMillis}ms)"
        )

        connection.pause()
        delay(200)

        assertTrue(
            abs(errorMillis) < STEADY_TOLERANCE_MILLIS,
            "over 3s of playback the position advanced $reported frames but $expected frames of audio " +
                "were heard: ${errorMillis}ms of error. Negative means the player under-reports, which " +
                "draws the playhead behind the sound."
        )
    }

    /**
     * Position tracking across pause/resume, with startup latency excluded rather than blamed.
     *
     * Each segment's clock starts only once the position is moving, so what is left is the accounting
     * error: the flushed queue that a resume replays, and nothing else. Any shortfall here is audio that
     * was heard but never counted; any excess is audio replayed.
     */
    @Test
    fun theReportedPositionDoesNotFallBehindAcrossPauseAndResume() = withPlayer { connection ->
        connection.play()
        awaitPlaybackStart(connection)

        val basePosition = connection.getLocationInFrames().toLong()
        var heardNanos = 0L
        val startups = mutableListOf<Long>()

        repeat(CYCLES) {
            // Audio is confirmed flowing at this point, so this stretch really was heard.
            val segmentStart = System.nanoTime()
            delay(400)
            heardNanos += System.nanoTime() - segmentStart

            connection.pause()
            delay(250) // paused: nothing is heard, and nothing is counted
            connection.play()
            // Excluded from `heard`: this is dead time, and charging it to position tracking is exactly
            // what made this measurement bimodal.
            startups += awaitPlaybackStart(connection)
        }
        val tailStart = System.nanoTime()
        delay(400)
        heardNanos += System.nanoTime() - tailStart

        val endPosition = connection.getLocationInFrames().toLong()
        val reported = endPosition - basePosition
        val heardFrames = heardNanos * spec.sampleRate / 1_000_000_000L
        val shortfallMillis = (heardFrames - reported) * 1_000 / spec.sampleRate
        println(
            "[AUDIO] $CYCLES pause/resume cycles: reported=$reported heard=$heardFrames " +
                "shortfall=${shortfallMillis}ms startups=${startups.sorted()}"
        )

        connection.pause()
        delay(200)

        assertTrue(
            shortfallMillis < CYCLE_TOLERANCE_MILLIS,
            "position advanced $reported frames while $heardFrames frames were heard — " +
                "${shortfallMillis}ms short, over stretches where audio was confirmed flowing. A playhead " +
                "drawn from this sits that far behind the sound."
        )
    }

    /**
     * How long a resume takes to produce sound, over and over. This is the subject, not a nuisance: the
     * reported symptom is "it doesn't start playing until I stop toggling", and this is that number.
     *
     * It asserts only a generous ceiling — the point is the printed distribution, and a tight assertion
     * would just reintroduce a flaky gate. The spread is the finding.
     */
    @Test
    fun theStartupLatencyOfEachResumeIsMeasured() = withPlayer { connection ->
        val latencies = mutableListOf<Long>()

        connection.play()
        latencies += awaitPlaybackStart(connection)

        repeat(STARTUP_SAMPLES) {
            delay(300)
            connection.pause()
            delay(200)
            connection.play()
            latencies += awaitPlaybackStart(connection)
        }

        val sorted = latencies.sorted()
        println(
            "[AUDIO] resume startup latency over ${sorted.size} samples: " +
                "min=${sorted.first()}ms median=${sorted[sorted.size / 2]}ms max=${sorted.last()}ms " +
                "all=$sorted"
        )

        connection.pause()
        delay(200)

        assertTrue(
            sorted.last() < STARTUP_CEILING_MILLIS,
            "a resume took ${sorted.last()}ms to produce sound (all: $sorted). Whatever the cause, past " +
                "this the transport looks broken to anyone using it."
        )
    }

    /**
     * The one exact measurement the hardware will give us.
     *
     * Every route to "how many frames have actually been played" is an estimate: `longFramePosition`
     * counts frames consumed into the native buffer rather than rendered, and `available()` only sees the
     * Java-side buffer, not whatever CoreAudio holds below it. `drain()` is the exception — it returns only
     * once everything queued has been consumed, so at that instant played == written, exactly.
     *
     * Writing a known number of frames and timing the drain therefore measures the whole pipeline's
     * latency: everything beyond the audio's own duration is the cost of getting it out. That number is the
     * floor on how precisely any position can be reported, and it is the number that decides whether a
     * lower-level audio layer is worth reaching for.
     */
    @Test
    fun theOutputPipelineLatencyIsMeasured() {
        val sink = openRealSink() ?: return
        try {
            val audioMillis = 1_000L
            val totalBytes = (spec.sampleRate * audioMillis / 1_000).toInt() * spec.bytesPerFrame
            val chunk = ByteArray(4_096)
            val samples = mutableListOf<Long>()

            repeat(LATENCY_SAMPLES) {
                sink.start()
                val startNanos = System.nanoTime()
                var written = 0
                while (written < totalBytes) {
                    val n = minOf(chunk.size, totalBytes - written)
                    val accepted = sink.write(chunk, 0, n)
                    if (accepted <= 0) break
                    written += accepted
                }
                // Returns when the hardware has consumed everything: played == written, exactly.
                sink.drain()
                val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
                samples += elapsedMillis - audioMillis
                sink.stop()
                sink.flush()
            }

            val sorted = samples.sorted()
            println(
                "[AUDIO] pipeline latency over ${sorted.size} samples of ${audioMillis}ms audio: " +
                    "min=${sorted.first()}ms median=${sorted[sorted.size / 2]}ms max=${sorted.last()}ms " +
                    "all=$sorted"
            )

            assertTrue(
                sorted.last() < LATENCY_CEILING_MILLIS,
                "the pipeline took ${sorted.last()}ms longer than the audio it was given (all: $sorted). " +
                    "That is the floor on position accuracy, and past this a lower-level audio layer " +
                    "starts to be worth the trouble."
            )
        } finally {
            sink.close()
        }
    }

    /**
     * The reported reproduction: toggle pause/play as fast as the transport will take it, then stop and see
     * whether audio comes back — "it doesn't even start playing until I stop toggling".
     *
     * The unhurried path is healthy (a resume produces sound in ~25ms, measured above), so if this symptom
     * is real it lives in overlapping control calls, not in the device. Three intervals are measured in one
     * run to show where it degrades: back-to-back, 20ms apart, and 60ms apart — roughly the fastest a person
     * can click.
     *
     * Every toggle pair ends in `play()`, so audio MUST be flowing afterwards. "Never started" is the
     * failure this is looking for, and it is reported per interval rather than aborting the run, so one bad
     * case still yields the whole curve.
     */
    @Test
    fun rapidTogglingStillEndsWithAudioPlaying() = withPlayer { connection ->
        connection.play()
        awaitPlaybackStart(connection)
        delay(300)

        val results = mutableListOf<String>()
        var anyFailed = false

        for (interval in TOGGLE_INTERVALS_MILLIS) {
            repeat(TOGGLES_PER_BURST) {
                connection.pause()
                if (interval > 0) delay(interval)
                connection.play()
                if (interval > 0) delay(interval)
            }

            // The last thing asked for was play. How long until sound, if ever?
            val startup = measurePlaybackStart(connection)
            val advanceBefore = connection.getLocationInFrames().toLong()
            delay(300)
            val advanced = connection.getLocationInFrames().toLong() - advanceBefore
            val advancedMillis = advanced * 1_000 / spec.sampleRate

            // THREE conditions, and every one of them has caught something this gate previously let
            // through. Asserting only that playback started passed while reporting advancedIn300ms=0 - the
            // position twitched once and stopped. Adding the lower bound then passed while reporting
            // advancedIn300ms=1405ms, which is the position moving at nearly five times real time: audio
            // does not do that, so whatever was being measured was not audio. Playback advances at 1x, and
            // a gate on "is it playing" has to say so in both directions.
            if (startup == null || advancedMillis < SUSTAINED_MILLIS || advancedMillis > EXCESS_MILLIS) {
                anyFailed = true
            }
            results += "interval=${interval}ms startup=${startup ?: -1}ms " +
                "advancedIn300ms=${advancedMillis}ms"

            // Settle before the next burst so each one starts from a known-good state.
            connection.pause()
            delay(400)
            connection.play()
            awaitPlaybackStart(connection)
            delay(200)
        }

        println("[AUDIO] $TOGGLES_PER_BURST toggles per burst:")
        results.forEach { println("[AUDIO]   $it") }

        connection.pause()
        delay(200)

        assertTrue(
            !anyFailed,
            "after a burst of rapid toggles ending in play(), the position did not advance the way playing " +
                "audio does: $results. Roughly 300ms of advance was expected in 300ms — materially less " +
                "means it never started or stalled, materially more means it is catching up rather than " +
                "playing. This is the reported symptom: the transport stops responding to the transport."
        )
    }

    /**
     * Suspends until the reported position has clearly moved, and returns how long that took. The
     * threshold is 10ms of audio, comfortably above position jitter and far below anything a person would
     * notice, so this detects "audio is flowing" without waiting for a round number.
     */
    private suspend fun awaitPlaybackStart(connection: AudioPlayerConnection): Long =
        measurePlaybackStart(connection) ?: fail(
            "the reported position never advanced within ${START_TIMEOUT_MILLIS}ms of play() — playback " +
                "did not start at all, or the position is not tracking it"
        )

    /** Milliseconds until the position clearly moves, or null if it never did. */
    private suspend fun measurePlaybackStart(
        connection: AudioPlayerConnection,
        timeoutMillis: Long = START_TIMEOUT_MILLIS
    ): Long? {
        val from = connection.getLocationInFrames().toLong()
        val threshold = spec.sampleRate / 100 // 10ms
        val startNanos = System.nanoTime()
        while (System.nanoTime() - startNanos < timeoutMillis * 1_000_000) {
            if (connection.getLocationInFrames().toLong() - from >= threshold) {
                return (System.nanoTime() - startNanos) / 1_000_000
            }
            delay(2)
        }
        return null
    }

    /** Builds a player on the real default output device, runs [body], and always tears down. */
    private fun withPlayer(body: suspend (AudioPlayerConnection) -> Unit) {
        val sink = openRealSink() ?: return
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val factory = AudioPlayerConnectionFactory.createForScope(sink, DefaultAudioProcessor(), scope)
            val connection = AudioPlayerConnection(1, factory, scope, Dispatchers.Default)
            runBlocking {
                connection.load(SilentReader(totalFrames = spec.sampleRate * 120, spec = spec))
                delay(300)
                body(connection)
            }
        } finally {
            scope.cancel()
            sink.close()
        }
    }

    /** Null when this machine has no usable output line, so the test reports nothing rather than failing. */
    private fun openRealSink(): JvmAudioSink? {
        val info = DataLine.Info(SourceDataLine::class.java, null)
        if (!AudioSystem.isLineSupported(info)) {
            println("[AUDIO] no SourceDataLine on this machine; skipping")
            return null
        }
        return try {
            JvmAudioSink { AudioSystem.getLine(info) as SourceDataLine }.apply { open(spec) }
        } catch (e: Exception) {
            println("[AUDIO] could not open an output line (${e.message}); skipping")
            null
        }
    }

    private companion object {
        const val CYCLES = 6
        const val STARTUP_SAMPLES = 8
        const val TOGGLES_PER_BURST = 12
        const val LATENCY_SAMPLES = 3

        /** Of the 300ms observation window, how much advance still counts as playing. */
        const val SUSTAINED_MILLIS = 200

        /**
         * And how much is too much. Audio advances at 1x; a position that covers materially more than the
         * window is not tracking playback, it is catching up to something — a clamp lifting, a queued
         * command landing late, an anchor applied twice.
         */
        const val EXCESS_MILLIS = 400

        /** Past this, the position cannot be drawn accurately no matter how good the accounting is. */
        const val LATENCY_CEILING_MILLIS = 400

        /** Back-to-back, then two paces a person could actually click at. */
        val TOGGLE_INTERVALS_MILLIS = listOf(0L, 20L, 60L)

        /** A HAL quantum plus slack. Steady playback should track the wall clock far better than this. */
        const val STEADY_TOLERANCE_MILLIS = 300

        /**
         * With startup excluded, what remains is the flushed queue a resume replays. That is bounded by the
         * buffer depth per cycle, and replay makes the shortfall NEGATIVE, so this only needs to catch
         * genuine under-reporting.
         */
        const val CYCLE_TOLERANCE_MILLIS = 250

        /** Deliberately generous: the printed distribution is the finding, not this gate. */
        const val STARTUP_CEILING_MILLIS = 1_500

        const val START_TIMEOUT_MILLIS = 5_000L
    }
}

/** Silence, sized to order. Only the frame accounting matters, and silence keeps the test quiet. */
internal class SilentReader(
    override val totalFrames: Int,
    override val spec: AudioSpec
) : AudioFileReader {

    override var framePosition: Int = 0
    private var isOpen = false

    override fun hasRemaining(): Boolean = framePosition < totalFrames

    override fun getPcmBuffer(bytes: ByteArray): Int {
        if (!isOpen || !hasRemaining()) return 0
        val framesRequested = bytes.size / spec.bytesPerFrame
        val framesToRead = minOf(framesRequested, totalFrames - framePosition)
        val bytesToReturn = framesToRead * spec.bytesPerFrame
        for (i in 0 until bytesToReturn) bytes[i] = 0
        framePosition += framesToRead
        return bytesToReturn
    }

    override fun seek(frame: Long) {
        framePosition = frame.toInt().coerceIn(0, totalFrames)
    }

    override fun open() {
        isOpen = true
    }

    override fun release() {
        isOpen = false
    }
}
