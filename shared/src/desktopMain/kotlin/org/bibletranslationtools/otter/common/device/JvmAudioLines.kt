package org.bibletranslationtools.otter.common.device

import org.bibletranslationtools.shared.logging.logDebug
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line

/**
 * Last-resort release of every audio line this process still holds open, on the way out.
 *
 * A safety net, and deliberately a modest one — worth being clear about what it does and does not buy:
 *
 * It does NOT protect the case that motivated it. A capture line leaked *between screens* is claimed
 * for as long as the app runs, and no amount of shutdown handling helps; that is fixed where it
 * happens, in [JvmAudioSource.open] (a re-open releases the line it replaces) and
 * [AudioRecorderConnectionFactory.releaseRecording] (teardown releases on a scope that outlives the
 * screen).
 *
 * Nor is it what ultimately frees the device: process exit does that on its own, because audio device
 * handles are per-process and the OS reclaims them. A leaked line cannot outlive the JVM.
 *
 * What it covers is the stretch in between, which is not always short — a JVM can linger after the last
 * window closes, and during development a hung run keeps the microphone claimed until it is killed. On
 * Windows, where a capture line is exclusive, that stretch is the difference between the user's next
 * app finding a microphone and not. It is cheap, contained, and asks nothing of the code that leaked.
 *
 * Installed as a JVM shutdown hook, which is the widest net available: it runs for a window close,
 * Cmd-Q / Alt-F4, `exitProcess`, SIGTERM and SIGINT. Nothing runs on SIGKILL or a hard force-quit, and
 * nothing needs to — that is the case where the OS reclaim above is the whole answer.
 */
object JvmAudioLines {

    private val hookInstalled = AtomicBoolean(false)

    /**
     * Registers [closeAllOpenLines] to run at JVM shutdown. Idempotent, so each app can call it from
     * its own `main` without coordinating, and a second call is a no-op rather than a second hook.
     */
    fun installShutdownHook() {
        if (!hookInstalled.compareAndSet(false, true)) return
        runCatching {
            Runtime.getRuntime().addShutdownHook(
                Thread({ closeAllOpenLines() }, "audio-line-release")
            )
        }
    }

    /**
     * Stops and closes every open line on every mixer.
     *
     * Goes through the mixers rather than through our own [AudioSource]/[AudioSink] objects on purpose:
     * the whole point of a net is to catch a line nothing holds a reference to any more, and only the
     * mixer still knows about that one. `Mixer.getSourceLines()`/`getTargetLines()` report the lines it
     * currently has OPEN, so this closes exactly what is still claimed and nothing else.
     *
     * Every step is guarded and none of them blocks. A shutdown hook that throws or waits turns a clean
     * exit into a hang, which would be a worse bug than the one this is insuring against.
     */
    fun closeAllOpenLines() {
        val closed = closeOpenLines(::openLinesOnEveryMixer)
        // Debug, not info: on a clean exit this is always zero, and a shutdown hook is no place to be
        // writing to a log that may already have been closed.
        logDebug(this) { "released $closed audio line(s) still open at shutdown" }
    }

    /** Every line the mixers currently report as OPEN — which is precisely what is still claimed. */
    private fun openLinesOnEveryMixer(): List<Line> {
        val mixers = runCatching { AudioSystem.getMixerInfo() }.getOrNull() ?: return emptyList()
        return mixers.flatMap { mixerInfo ->
            val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull()
                ?: return@flatMap emptyList()
            runCatching { mixer.sourceLines.toList() + mixer.targetLines.toList() }
                .getOrDefault(emptyList())
        }
    }

    /**
     * The sweep itself, over a supplied set of lines so it can be exercised without opening real
     * hardware — a test that had to open the microphone to check the shutdown net would be a poor trade.
     *
     * @return how many lines were actually released.
     */
    internal fun closeOpenLines(lines: () -> List<Line>): Int {
        val open = runCatching { lines() }.getOrNull() ?: return 0
        var closed = 0
        for (line in open) {
            if (release(line)) closed++
        }
        return closed
    }

    private fun release(line: Line): Boolean {
        if (!runCatching { line.isOpen }.getOrDefault(false)) return false
        // stop() and flush() before close(): a line still running is the one most likely to be a leak,
        // and closing a running capture line outright is the operation that has been known to hang.
        if (line is DataLine) {
            runCatching { line.stop() }
            runCatching { line.flush() }
        }
        return runCatching { line.close() }.isSuccess
    }
}
