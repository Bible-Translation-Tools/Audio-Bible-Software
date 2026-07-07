package org.bibletranslationtools.bttrecorder2.ui.playback

import androidx.compose.runtime.mutableLongStateOf

/**
 * The display-side playback clock: a rate-locked position that advances at the audio
 * sample rate on the display's frame clock, slew-corrected toward the player's real
 * (chunky) position. This is what the waveform/minimap draw each frame.
 *
 * Why not draw the player position directly: getLocationInFrames() advances in HAL
 * quanta (~10–50 ms desktop, up to ~186 ms Android), which reads as stutter. Why not
 * snap to it whenever it advances: each snap is a visible forward jump (the old
 * ticker's hiccup). Instead the clock free-runs at sampleRate and folds the observed
 * error in exponentially (τ = 120 ms, frame-rate independent), which turns chunky or
 * momentarily-behind readings into invisible speed adjustments. A hard snap happens
 * only for errors > 250 ms (seek-sized; threshold must stay ABOVE Android's worst-case
 * head-position quantum or the hiccups return).
 *
 * Startup/seek transients: until the sink is actually running, the player reports the
 * WRITE cursor — ahead of anything audible. [positionReliable] gates ALL corrections:
 * while false the clock free-runs at exactly 1.0× from the last snapTo (which is
 * correct — audio starts from that very frame). Timer-based grace windows were tried
 * and produced either a speed wobble (chasing the lie) or a deferred hard snap
 * (ignoring it past the window).
 *
 * Threading: onFrame/snapTo and all property writes MUST happen on the main thread.
 * [displayFrame] is snapshot state — read it ONLY inside draw lambdas (or gesture
 * handlers); a composition-scope read would reinstate per-frame recomposition.
 */
class PlaybackDisplayClock(
    private val positionSource: () -> Long,
    private val positionReliable: () -> Boolean = { true }
) {
    private val displayFrameState = mutableLongStateOf(0L)
    val displayFrame: Long get() = displayFrameState.longValue

    // Non-snapshot internals — main thread only.
    private var posF = 0.0
    private var lastNanos = -1L
    // Convergence latch: after snapTo, the player's async seek is still in flight,
    // so the source keeps reporting the PRE-seek position for a few frames (with the
    // sink running — "reliable" — the whole time). Corrections stay off until the
    // source has agreed with the display once (entered the slew band); a latch,
    // not a timer, so it can never expire into a deferred jump.
    private var sourceSettled = false
    private var unsettledSinceNanos = -1L
    // DC estimate of the source error. The raw error carries a ±30 ms sawtooth from
    // the mixer's drain quantum; slewing on it directly modulates the scroll speed
    // at the sawtooth rate (perceived as a sinusoidal "wave"). Averaging first (τ
    // = 300 ms) cancels the periodic part; only true drift survives to be corrected.
    private var errAvg = 0.0
    var sampleRate: Int = 44100
    var durationFrames: Long = 0

    /** True while the clock should follow playback (= playing && !follow-frozen). */
    var advancing: Boolean = false

    // PERF: debug-only counter (read by PlaybackPerfStats logging); a non-zero rate
    // during continuous playback means the slew band is being exceeded.
    var hardSnaps: Int = 0
        private set

    fun onFrame(frameTimeNanos: Long) {
        if (!advancing) {
            lastNanos = frameTimeNanos
            return
        }
        if (lastNanos < 0) {
            lastNanos = frameTimeNanos
            return
        }
        val dt = (frameTimeNanos - lastNanos) / 1e9
        lastNanos = frameTimeNanos

        posF += dt * sampleRate                                   // rate lock

        // Corrections only when the source reflects the audible position; while the
        // sink is spinning up (or a seek is settling) we free-run at exactly 1.0×.
        if (positionReliable()) {
            val error = positionSource().toDouble() - posF
            val errorMs = (error * 1000.0 / sampleRate).toInt()
            val inBand = kotlin.math.abs(error) <= sampleRate * 0.25

            if (!sourceSettled) {
                if (inBand) {
                    sourceSettled = true
                    unsettledSinceNanos = -1L
                    errAvg = 0.0
                    PlaybackPerfStats.onClockEvent("settled errMs=$errorMs")
                } else {
                    // Source still reporting a stale/lying position after a snap —
                    // free-run. Safety valve: if it never converges (a genuinely
                    // failed/rejected seek), accept the source after 2 s rather
                    // than drift forever.
                    if (unsettledSinceNanos < 0) unsettledSinceNanos = frameTimeNanos
                    if (frameTimeNanos - unsettledSinceNanos > 2_000_000_000L) {
                        PlaybackPerfStats.onClockEvent("FALLBACK snap errMs=$errorMs (never settled)")
                        posF += error
                        errAvg = 0.0
                        hardSnaps++
                        sourceSettled = true
                        unsettledSinceNanos = -1L
                    }
                }
            } else {
                if (inBand) {
                    // Two-stage correction: average out the periodic sawtooth (τ =
                    // 300 ms), then gently steer toward the remaining DC drift (τ =
                    // 500 ms). Speed stays visually constant; drift still converges
                    // within ~1 s.
                    errAvg += (error - errAvg) * (1 - kotlin.math.exp(-dt / 0.3))
                    posF += errAvg * (1 - kotlin.math.exp(-dt / 0.5))
                } else {
                    PlaybackPerfStats.onClockEvent("HARD snap errMs=$errorMs")
                    posF += error                                     // >250 ms: seek-sized
                    errAvg = 0.0
                    hardSnaps++
                }
            }
            PlaybackPerfStats.onClockFrame(errorMs, sourceSettled)
        } else {
            PlaybackPerfStats.onClockFrame(null, sourceSettled)
        }
        posF = posF.coerceIn(0.0, durationFrames.toDouble())
        displayFrameState.longValue = posF.toLong()
    }

    /** Jump the display position (seek / scrub / load / pause-commit). */
    fun snapTo(frame: Long) {
        posF = frame.coerceIn(0L, durationFrames).toDouble()
        lastNanos = -1L
        sourceSettled = false          // distrust the source until it re-converges
        unsettledSinceNanos = -1L
        errAvg = 0.0
        displayFrameState.longValue = posF.toLong()
        PlaybackPerfStats.onClockEvent("snapTo frame=${posF.toLong()}")
    }
}
