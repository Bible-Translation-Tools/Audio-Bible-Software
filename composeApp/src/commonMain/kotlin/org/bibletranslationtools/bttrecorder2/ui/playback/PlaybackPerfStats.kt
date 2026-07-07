package org.bibletranslationtools.bttrecorder2.ui.playback

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// PERF: temporary Phase-0 instrumentation — remove after playback rework validation.
// Flip to false to fully no-op every counter call (still branches once per call, which
// is cheap enough to leave compiled in during Phase 0-6 baselining).
const val PLAYBACK_PERF_STATS = true

/**
 * Temporary, debug-flagged performance counters for the playback screen, used to
 * establish a baseline before the live-rendering rework (see Phase 0 of the
 * playback-jank plan). Every recorder is a cheap atomic increment that no-ops
 * when [PLAYBACK_PERF_STATS] is false. [startLogging] prints one summary line per
 * second and resets the per-second counters.
 */
object PlaybackPerfStats {
    // @PublishedApi internal (rather than private) so the public inline record
    // functions below can access them directly without an extra function-call hop.
    @PublishedApi internal val emissions = AtomicInteger(0)
    @PublishedApi internal val renders = AtomicInteger(0)
    @PublishedApi internal val renderNanosTotal = AtomicLong(0L)
    @PublishedApi internal val recompositions = AtomicInteger(0)
    @PublishedApi internal val frames = AtomicInteger(0)
    @PublishedApi internal val framesOver20ms = AtomicInteger(0)
    @PublishedApi internal val diskBytesRead = AtomicLong(0L)

    private val loggingStarted = AtomicBoolean(false)

    /** Call on every `_uiState` emission (via the VM's `updateState` helper). */
    inline fun onEmission() {
        if (!PLAYBACK_PERF_STATS) return
        emissions.incrementAndGet()
    }

    /** Call after each waveform render, with its wall time and estimated disk bytes read. */
    inline fun onRender(durationNanos: Long, bytesRead: Long) {
        if (!PLAYBACK_PERF_STATS) return
        renders.incrementAndGet()
        renderNanosTotal.addAndGet(durationNanos)
        diskBytesRead.addAndGet(bytesRead)
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

    // ── Clock telemetry (written on main by PlaybackDisplayClock; read by logger) ──
    @Volatile @PublishedApi internal var clkErrLastMs: Int = 0
    @Volatile @PublishedApi internal var clkErrMinMs: Int = Int.MAX_VALUE
    @Volatile @PublishedApi internal var clkErrMaxMs: Int = Int.MIN_VALUE
    @Volatile @PublishedApi internal var clkReliableFrames: Int = 0
    @Volatile @PublishedApi internal var clkUnreliableFrames: Int = 0
    @Volatile @PublishedApi internal var clkSettled: Boolean = false

    /** Call once per advancing clock frame: source error vs display, in ms. */
    inline fun onClockFrame(errorMs: Int?, settled: Boolean) {
        if (!PLAYBACK_PERF_STATS) return
        clkSettled = settled
        if (errorMs == null) {
            clkUnreliableFrames++
            return
        }
        clkReliableFrames++
        clkErrLastMs = errorMs
        if (errorMs < clkErrMinMs) clkErrMinMs = errorMs
        if (errorMs > clkErrMaxMs) clkErrMaxMs = errorMs
    }

    /** Discrete clock event (settle latched, hard snap, fallback snap) — logged immediately. */
    fun onClockEvent(message: String) {
        if (!PLAYBACK_PERF_STATS) return
        println("[CLOCK] $message")
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
                val rendersPerSec = renders.getAndSet(0)
                val renderNanosPerSec = renderNanosTotal.getAndSet(0L)
                val recompositionsPerSec = recompositions.getAndSet(0)
                val framesPerSec = frames.getAndSet(0)
                val framesOver20msPerSec = framesOver20ms.getAndSet(0)
                val diskBytesPerSec = diskBytesRead.getAndSet(0L)

                val renderMsAvg = if (rendersPerSec > 0) {
                    (renderNanosPerSec.toDouble() / rendersPerSec) / 1_000_000.0
                } else {
                    0.0
                }
                val diskKBPerSec = diskBytesPerSec / 1024.0

                val errMin = clkErrMinMs
                val errMax = clkErrMaxMs
                val clkStats = if (clkReliableFrames > 0) {
                    "clkErrMs[min=$errMin last=$clkErrLastMs max=$errMax] " +
                        "rel=${clkReliableFrames}/${clkReliableFrames + clkUnreliableFrames} settled=$clkSettled"
                } else {
                    "clk[unreliable=${clkUnreliableFrames} settled=$clkSettled]"
                }
                clkErrMinMs = Int.MAX_VALUE
                clkErrMaxMs = Int.MIN_VALUE
                clkReliableFrames = 0
                clkUnreliableFrames = 0

                println(
                    "[PERF] emissions/s=$emissionsPerSec renders/s=$rendersPerSec " +
                        "renderMsAvg=${"%.2f".format(renderMsAvg)} recomp/s=$recompositionsPerSec " +
                        "frames/s=$framesPerSec framesOver20ms=$framesOver20msPerSec " +
                        "diskKB/s=${"%.1f".format(diskKBPerSec)} $clkStats"
                )
            }
        }
    }
}
