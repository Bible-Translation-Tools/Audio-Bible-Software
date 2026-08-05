package org.bibletranslationtools.shared.audio.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioTransportHarness
import org.bibletranslationtools.otter.common.device.AudioTransportHarness.Companion.BUFFER_FRAMES
import org.bibletranslationtools.otter.common.device.withTransport
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the drawn playhead can move BACKWARDS while audio is playing forwards.
 *
 * Every visualisation — waveform, minimap, elapsed time — reads [PlaybackDisplayPosition.displayFrame],
 * so a backwards step there is a visible jump back. The clock corrects itself toward the player's
 * position, and for errors above the slew band (250 ms) that correction is a hard snap in whichever
 * direction the error points. So any moment where the player reports a position *behind* the display
 * while claiming to be reliable becomes a jump on screen.
 *
 * This drives a real clock from a real connection at display-frame rate, in the same order
 * `PlaybackViewModel` does, and records every backwards step of the drawn position.
 */
class PlaybackDisplayJumpTest {

    /**
     * A backwards step is only a bug where playback is moving forwards, so the scenario contains no
     * backwards seek: play, pause, resume, seek forward, play on to the end.
     */
    @Test
    fun theDrawnPositionNeverMovesBackwardsDuringForwardPlayback() = runTest {
        withTransport(takeFrames = OUTLASTING_TAKE_FRAMES) {
            val connection = connection()
            val probe = ClockProbe(this, connection, connectionId = 1)

            loadAndPlay(connection)
            probe.startAdvancing() // what the host does on AudioPlayerEvent.Play
            probe.drive(200)

            // Pause exactly as PlaybackViewModel.togglePlayPause does: freeze at the DISPLAYED
            // position and snap there, rather than polling the player (which reports the write
            // cursor once the sink stops).
            probe.freezeAtDisplayedPosition()
            connection.pause()
            awaitEvent("Pause")
            probe.drive(50)

            // Resume immediately, the case that used to have its play() dropped.
            connection.play()
            probe.startAdvancing()
            probe.drive(250)

            // A forward seek, committed to the display from the requested frame (applyTransportForFrame).
            val target = probe.displayFrame().toInt() + BUFFER_FRAMES * 50
            connection.seek(target)
            probe.snapTo(target.toLong())
            probe.drive(250)

            assertTrue(
                probe.samples > 20,
                "the probe must actually have sampled the clock, was ${probe.samples} frames"
            )
            assertTrue(
                probe.backwardsSteps.isEmpty(),
                "the drawn position moved backwards ${probe.backwardsSteps.size} time(s): " +
                    "${probe.backwardsSteps.take(5)} (frames, negative = jump back)"
            )
        }
    }

    /**
     * The audio device stalls mid-take: the position stops advancing and stops being reliable for a
     * while, then comes back exactly where it left off. No transport call, no second player, nothing
     * the user did.
     *
     * This is what an underrun looks like from the clock's side, and it is not exotic. The playback
     * loop reads from disk and writes to the line on the same thread, so a disk hiccup or a GC pause
     * empties the line's buffer — and JavaSound clears `SourceDataLine.isRunning` the moment that
     * happens ("presentation ceases ... because playback completes"), which is what
     * `JvmAudioSink.isRunning` and therefore `isPositionReliable` report.
     *
     * The clock free-runs at 1.0× while the position is unreliable, on the assumption that unreliable
     * means "playback is about to start from the frame we just snapped to". During a stall that
     * assumption is inverted: audio is frozen, so free-running walks the display away from the audio,
     * and when the position comes back the error is seek-sized and gets hard-snapped — backwards.
     */
    @Test
    fun theDrawnPositionDoesNotJumpBackWhenTheAudioDeviceStalls() {
        val sampleRate = 44_100
        val framesPerDisplayFrame = (sampleRate * FRAME_MILLIS / 1000).toInt()
        var audibleFrame = 0L
        var reliable = true

        val clock = PlaybackDisplayPosition(
            positionSource = { audibleFrame },
            positionReliable = { reliable }
        ).apply {
            this.sampleRate = sampleRate
            durationFrames = sampleRate.toLong() * 30
        }
        clock.snapTo(0L)
        clock.advancing = true

        var nanos = 0L
        var previous = 0L
        val backwardsSteps = mutableListOf<Long>()
        fun displayFrame(audioAdvances: Boolean) {
            nanos += FRAME_MILLIS * 1_000_000
            if (audioAdvances) audibleFrame += framesPerDisplayFrame
            clock.onFrame(nanos)
            val current = clock.displayFrame
            if (current < previous) backwardsSteps += (current - previous)
            previous = current
        }

        repeat(120) { displayFrame(audioAdvances = true) } // ~2 s of normal playback; the clock settles

        // The line's buffer empties: audio frozen, position unreliable, for ~400 ms.
        reliable = false
        repeat(25) { displayFrame(audioAdvances = false) }

        // Data flows again and playback continues from exactly where it stopped.
        reliable = true
        repeat(120) { displayFrame(audioAdvances = true) }

        assertTrue(
            backwardsSteps.isEmpty(),
            "the drawn position moved backwards ${backwardsSteps.size} time(s): $backwardsSteps " +
                "(frames; a 400 ms stall is ${sampleRate * 400 / 1000} frames)"
        )
    }

    /**
     * The same probe, with a second player using the shared worker — the source-audio player, or a take
     * preview in a list.
     *
     * `IAudioPlayer.events` used to BE the shared worker's stream, so this host acted on another
     * player's transitions as if they were its own. The measured result: the other clip's `Complete`
     * parked this display at the end of a take that was still mid-playback (a ~9 s forward teleport),
     * and then pressing play rewound the display to zero, because a display parked at the end looks
     * like a take that finished. Two jumps, neither of them anything the user did.
     */
    @Test
    fun theDrawnPositionDoesNotJumpBackWhenAnotherPlayerUsesTheSharedWorker() = runTest {
        withTransport(takeFrames = OUTLASTING_TAKE_FRAMES) {
            val takePlayer = connection(id = 1)
            val otherPlayer = connection(id = 2)
            val probe = ClockProbe(this, takePlayer, connectionId = 1)

            loadAndPlay(takePlayer)
            probe.drive(200) // the probe applies the host's own event handling as events arrive

            // Something else plays: a shorter clip, on the same hardware.
            otherPlayer.load(newTake(frames = BUFFER_FRAMES * 40))
            awaitEvent("Load")
            otherPlayer.play()
            awaitEvent("Play")
            probe.drive(400) // long enough for the other clip to finish

            // The user goes back to the take and presses play again.
            takePlayer.play()
            probe.startAdvancing()
            probe.drive(300)

            assertTrue(
                probe.samples > 20,
                "the probe must actually have sampled the clock, was ${probe.samples} frames"
            )
            assertTrue(
                probe.backwardsSteps.isEmpty(),
                "the drawn position moved backwards ${probe.backwardsSteps.size} time(s): " +
                    "${probe.backwardsSteps.take(5)} (frames, negative = jump back)"
            )
        }
    }
}

/** One display frame at 60 Hz. */
private const val FRAME_MILLIS = 16L

/**
 * A take that cannot finish while the scenario is still running. These tests drive the clock for the best
 * part of a second in real time, and a take that completes mid-scenario changes what the host does — it
 * parks the display at the end, and the next `startAdvancing()` legitimately rewinds to 0. That is
 * correct behaviour producing a backwards step, so the take has to outlast the scenario or the test
 * fails on a loaded machine for a reason that is not a bug.
 */
private const val OUTLASTING_TAKE_FRAMES = 1024 * 2000

/**
 * Drives a [PlaybackDisplayPosition] at display-frame rate against a live connection and records every
 * backwards movement of the drawn position.
 */
private class ClockProbe(
    private val harness: AudioTransportHarness,
    connection: AudioPlayerConnection,
    private val connectionId: Int
) {
    private val clock = PlaybackDisplayPosition(
        positionSource = { connection.getLocationInFrames().toLong() },
        positionReliable = { connection.isPositionReliable() }
    ).apply {
        sampleRate = SAMPLE_RATE
        durationFrames = harness.takeFrames.toLong()
    }

    /** Every backwards step of the drawn position, as a negative frame delta. */
    val backwardsSteps = mutableListOf<Long>()

    /** Every step, either direction, far larger than one display frame of normal advance. */
    val jumps = mutableListOf<Long>()
    var samples = 0
        private set

    private var previous = 0L
    private var nanos = 0L
    private var eventCursor = 0

    fun displayFrame(): Long = clock.displayFrame

    // None of these reset `previous`: a jump caused by a host transition is still a jump the user
    // sees, and masking it here is what hid half of this bug the first time round.
    fun startAdvancing() = clock.startAdvancing()

    /**
     * Applies the host's transport-event handling, copied from `PlaybackViewModel`'s collector, to the
     * events this connection actually receives — i.e. what `IAudioPlayer.events` delivers.
     */
    private fun applyHostEventHandling() {
        val events = harness.eventsOf(connectionId)
        while (eventCursor < events.size) {
            when (events[eventCursor++]) {
                "Play" -> clock.startAdvancing()
                "Pause" -> clock.advancing = false
                "Stop" -> {
                    clock.advancing = false
                    clock.snapTo(clock.displayFrame)
                }
                "Complete" -> {
                    clock.advancing = false
                    clock.snapTo(clock.durationFrames)
                }
                else -> Unit // Load carries no display transition
            }
        }
    }

    fun freezeAtDisplayedPosition() {
        val displayed = clock.displayFrame
        clock.advancing = false
        clock.snapTo(displayed)
    }

    fun snapTo(frame: Long) = clock.snapTo(frame)

    /** Runs the clock for [millis] of real time at ~60 Hz, recording backwards steps. */
    suspend fun drive(millis: Long) {
        val frames = millis / FRAME_MILLIS
        repeat(frames.toInt()) {
            delay(FRAME_MILLIS)
            applyHostEventHandling()
            // Synthetic frame times: a real display clock is regular, and using wall time here would
            // let a scheduling hiccup masquerade as a position error.
            nanos += FRAME_MILLIS * 1_000_000
            clock.onFrame(nanos)
            samples++
            val current = clock.displayFrame
            val step = current - previous
            if (step < 0) backwardsSteps += step
            // One display frame of normal playback is sampleRate * 16ms ≈ 706 frames; anything past a
            // few of those is a jump rather than playback.
            if (kotlin.math.abs(step) > SAMPLE_RATE * FRAME_MILLIS * 4 / 1000) jumps += step
            previous = current
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
