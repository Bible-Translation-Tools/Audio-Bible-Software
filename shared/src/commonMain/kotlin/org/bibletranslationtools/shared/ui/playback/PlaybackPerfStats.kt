package org.bibletranslationtools.shared.ui.playback

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// PERF: playback rendering instrumentation. Left compiled in (each recorder no-ops on
// this flag) so the Android validation pass — where the audio HAL, and therefore the
// display clock's correction behavior, differs from desktop — can re-enable it with a
// one-line change. Flip to true to emit one [PERF] summary line per second.
const val PLAYBACK_PERF_STATS = false

/**
 * Debug-flagged performance counters for the playback screen. Each recorder is a cheap
 * atomic increment that no-ops when [PLAYBACK_PERF_STATS] is false. [startLogging]
 * prints one summary line per second and resets the per-second counters.
 *
 * These verified the live-rendering rework against its baseline (see the playback-jank
 * plan). The three that remain — uiState emissions, PlaybackScreen recompositions, and
 * frame pacing — are the ones that distinguish "smooth" from "janky"; disk-read and
 * render-time counters were dropped with the disk renderer they measured.
 */
object PlaybackPerfStats {
    // @PublishedApi internal (rather than private) so the public inline record
    // functions below can access them directly without an extra function-call hop.
    @PublishedApi internal val emissions = AtomicInteger(0)
    @PublishedApi internal val recompositions = AtomicInteger(0)
    @PublishedApi internal val frames = AtomicInteger(0)
    @PublishedApi internal val framesOver20ms = AtomicInteger(0)

    private val loggingStarted = AtomicBoolean(false)

    /** Call on every `_uiState` emission (via the VM's `updateState` helper). */
    inline fun onEmission() {
        if (!PLAYBACK_PERF_STATS) return
        emissions.incrementAndGet()
    }

    /** Call once per PlaybackScreen recomposition (via a top-level `SideEffect`). */
    inline fun onRecomposition() {
        if (!PLAYBACK_PERF_STATS) return
        recompositions.incrementAndGet()
    }

    /** Call once per observed display frame, with the delta since the previous frame. */
    inline fun onFrame(dtNanos: Long) {
        if (!PLAYBACK_PERF_STATS) return
        frames.incrementAndGet()
        if (dtNanos > 20_000_000L) framesOver20ms.incrementAndGet()
    }

    /**
     * Launches (once) a background loop that prints one summary line per second and
     * resets the per-second counters. Safe to call from multiple sites; only the
     * first call actually starts the loop.
     */
    fun startLogging(scope: CoroutineScope) {
        if (!PLAYBACK_PERF_STATS) return
        if (!loggingStarted.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                delay(1000)

                val emissionsPerSec = emissions.getAndSet(0)
                val recompositionsPerSec = recompositions.getAndSet(0)
                val framesPerSec = frames.getAndSet(0)
                val framesOver20msPerSec = framesOver20ms.getAndSet(0)

                println(
                    "[PERF] emissions/s=$emissionsPerSec recomp/s=$recompositionsPerSec " +
                        "frames/s=$framesPerSec framesOver20ms=$framesOver20msPerSec"
                )
            }
        }
    }
}
