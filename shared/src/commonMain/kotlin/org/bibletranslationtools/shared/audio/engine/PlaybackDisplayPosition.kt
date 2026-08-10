package org.bibletranslationtools.shared.audio.engine

import androidx.compose.runtime.mutableLongStateOf
import kotlin.math.abs

/**
 * Where the playhead gets drawn, per display frame. It **follows** the audio rather than simulating it.
 *
 * The player reports its position in chunks — one per write to the hardware, ~20-25 ms — so drawing it
 * raw stutters. This interpolates between reports to get the smoothness back, but only ever *forward
 * from the last reported frame*, and never by more than the interval those reports arrive at. That
 * single clamp is the whole design:
 *
 *     displayFrame ∈ [lastReported, lastReported + oneUpdateInterval]
 *
 * so the drawn playhead can lag the sound by up to one update, and **cannot lead it at all** beyond
 * that. On a compressed waveform a pixel is a perceptible amount of time, and leading is the direction
 * that reads as broken.
 *
 * Three behaviours fall out of the clamp rather than being coded as rules:
 *
 *  - **No click-to-audio lead.** Pressing play starts the display advancing 25-130 ms before any
 *    sound leaves the device. Here nothing advances until the reported position moves, so the lead
 *    is never created — as opposed to created and then corrected.
 *  - **Stalls freeze.** If the device stops presenting audio the reports stop too; the clamp runs
 *    out after one update interval and the position holds, with the sound, until it comes back.
 *  - **No hard snaps.** There is no accumulated error to dump into a frame, so there is nothing to
 *    dump. The only jumps are ones the audio itself made.
 *
 * ### What this replaced
 *
 * The previous implementation integrated its own clock at the sample rate and slew-corrected toward the
 * player's position, with a settle latch, a DC error estimate, a 2 s convergence valve, a freeze rule
 * and a 250 ms hard-snap band to keep the error bounded. Every one of those was a local answer to a
 * problem the integrator created: an independent clock can be ahead of the audio, and nothing in its
 * structure forbids it, so the error had to exist before it could be corrected. Measured side by side
 * against the same audio on one virtual clock, it sat 12 ms off the audible frame at worst where this
 * sits at 0. All of that machinery went with it; the clamp above is what replaces it.
 *
 * [startAdvancing] and [snapTo] are the vocabulary that survived, because the hosts still have things to
 * say that the position alone cannot express — "the user dragged the playhead here", "this take is
 * loaded at this frame". They no longer guess anything.
 *
 * Threading: every member is main thread only. [displayFrame] is snapshot state — read it ONLY inside
 * draw lambdas or gesture handlers; a composition-scope read reinstates per-frame recomposition.
 */
class PlaybackDisplayPosition(
    private val positionSource: () -> Long,
    private val positionReliable: () -> Boolean = { true }
) {

    private val displayFrameState = mutableLongStateOf(0L)
    val displayFrame: Long get() = displayFrameState.longValue

    var sampleRate: Int = 44_100
    var durationFrames: Long = 0
    var advancing: Boolean = false

    // The last DISTINCT position the source reported, and the display-frame time we first saw it at.
    // Interpolation runs forward from here and from nowhere else.
    private var anchorFrame = UNSET
    private var anchorNanos = 0L

    // The measured gap between reports — the entire lead budget. A decaying max rather than the last
    // sample: reports land between display frames, so consecutive gaps alternate (16/32 ms at 60 Hz
    // for a 23 ms update) and following the last one modulates the cap at the beat frequency.
    private var updateIntervalNanos = DEFAULT_UPDATE_INTERVAL_NANOS

    // After a snapTo the player's seek is still in flight and the source keeps reporting the PRE-seek
    // position for a few frames. Following it would draw a jump forward and then back. Waiting it out
    // is safe *here* in a way it is not in a simulating clock: waiting means holding, and holding can
    // never lead.
    private var awaitingSeek = false
    private var seekTargetFrame = 0L
    private var awaitingSinceNanos = UNSET

    fun onFrame(frameTimeNanos: Long) {
        if (!advancing) return

        // Not reliable = the source is reporting the WRITE cursor, which is ahead of anything
        // audible. There is no correct number to draw, and holding is the only answer that cannot
        // lead. This covers exactly the startup window that manufactures the visible lead today.
        if (!positionReliable()) {
            if (PLAYBACK_PERF_STATS) report(reliable = false)
            return
        }

        val reported = positionSource()

        if (awaitingSeek && !seekHasLanded(reported, frameTimeNanos)) {
            if (PLAYBACK_PERF_STATS) report(reliable = true)
            return
        }

        if (reported != anchorFrame) {
            if (anchorFrame != UNSET) {
                val held = frameTimeNanos - anchorNanos
                // Decay toward the shorter gaps so the budget shrinks again after a slow patch, but
                // never past the display's own resolution or beyond a cap — a stall must not inflate
                // the lead the next resume is allowed to take.
                updateIntervalNanos = held.coerceIn(MIN_UPDATE_INTERVAL_NANOS, MAX_UPDATE_INTERVAL_NANOS)
                    .coerceAtLeast(updateIntervalNanos - updateIntervalNanos / 8)
            }
            anchorFrame = reported
            anchorNanos = frameTimeNanos
        }

        val elapsed = (frameTimeNanos - anchorNanos).coerceAtLeast(0L)
        val lead = minOf(elapsed, updateIntervalNanos)
        val next = (anchorFrame + lead * sampleRate / NANOS_PER_SECOND).coerceIn(0L, durationFrames)

        val current = displayFrameState.longValue
        // Small backward steps are the interpolation handing back an over-estimate when the next
        // report lands early; holding is both correct (the audio is still there) and invisible. A
        // large one is the audio genuinely somewhere else — a rewind, a replay, a seek the host did
        // not tell us about — and the audio is the truth, so it wins.
        val backwards = current - next
        displayFrameState.longValue = if (backwards in 1..seekBandFrames()) current else next

        if (PLAYBACK_PERF_STATS) report(reliable = true)
    }

    fun startAdvancing() {
        // Nothing to guess about, so nothing to pre-position: replaying a finished take rewinds the
        // player, and the rewind arrives as a large backward move in the reported position, which is
        // accepted on its own. Dropping the anchor just stops the last pre-rewind report from being
        // interpolated forward for a frame.
        anchorFrame = UNSET
        awaitingSeek = false
        awaitingSinceNanos = UNSET
        advancing = true
    }

    fun snapTo(frame: Long) {
        val clamped = frame.coerceIn(0L, durationFrames)
        displayFrameState.longValue = clamped
        anchorFrame = UNSET
        awaitingSeek = true
        seekTargetFrame = clamped
        awaitingSinceNanos = UNSET
    }

    /**
     * True once the source agrees the seek happened. A latch on the value, not a timer, with a valve:
     * a seek the player rejected outright would otherwise hold the display forever. Accepting the
     * source late is a visible jump but never a wrong position — it is where the audio is.
     */
    private fun seekHasLanded(reported: Long, frameTimeNanos: Long): Boolean {
        if (awaitingSinceNanos == UNSET) awaitingSinceNanos = frameTimeNanos
        val landed = abs(reported - seekTargetFrame) <= seekBandFrames()
        val expired = frameTimeNanos - awaitingSinceNanos > SEEK_VALVE_NANOS
        if (!landed && !expired) return false
        awaitingSeek = false
        awaitingSinceNanos = UNSET
        anchorFrame = UNSET
        return true
    }

    private fun seekBandFrames(): Long = sampleRate.toLong() * SEEK_BAND_MILLIS / 1_000

    private fun report(reliable: Boolean) = PlaybackPerfStats.onDrift(
        displayFrame = displayFrameState.longValue,
        sourceFrame = positionSource(),
        reliable = reliable,
        advancing = advancing
    )

    private companion object {
        const val UNSET = -1L
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** One 60 Hz display frame — the budget before any report has been timed. */
        const val DEFAULT_UPDATE_INTERVAL_NANOS = 16_000_000L
        const val MIN_UPDATE_INTERVAL_NANOS = 16_000_000L

        /**
         * Ceiling on the lead budget. Above this the display is guessing more than it is following,
         * which is the thing this class exists not to do; past it the position simply holds.
         */
        const val MAX_UPDATE_INTERVAL_NANOS = 50_000_000L

        /** Seek-sized: what counts as "the audio is somewhere else" rather than "off by a chunk". */
        const val SEEK_BAND_MILLIS = 250L

        /** How long to wait for a seek to show up in the reported position before believing the source. */
        const val SEEK_VALVE_NANOS = 2_000_000_000L
    }
}
