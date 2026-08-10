package org.bibletranslationtools.otter.common.device

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What [AudioSink.isRunning] has to mean, which is not what `SourceDataLine.isRunning()` means.
 *
 * Two consumers depend on it, both of them asking the same question — *is `framePosition` the audible
 * position, or a stand-in?* [AudioBufferPlayer.getLocationInFrames] uses it to choose between the sink's
 * position and the write cursor, and [AudioBufferPlayer.isPositionReliable] hands it to the display
 * clock. Neither is asking "is the mixer presenting data at this instant".
 *
 * JavaSound's `isRunning()` answers the second question: per its contract a line "begins running when
 * the first data is presented ... and continues until presentation ceases", and presentation ceases the
 * moment the buffer empties. So a starved line — a disk hiccup, a GC pause, the playback loop reading
 * and writing on one thread — reports itself not running while its `framePosition` remains perfectly
 * valid, because `writtenFrames - queuedFrames` is still exactly what has been heard.
 *
 * Passing that through cost us the same bug twice, in both directions. The display clock free-ran during
 * those windows and then hard-snapped backwards when the position returned (the playhead "jumping
 * back"); freezing the display instead — correct for a genuine stall — turned it into the playhead
 * falling seconds behind the audio, growing with every pause, because these windows are frequent and
 * long. Both are the same lie told to the same consumer.
 *
 * A stopped line is different, and must still report false: `framePosition` then falls back to the write
 * cursor, which really is ahead of anything audible.
 */
class JvmAudioSinkRunningTest {

    private val line = FakeSourceDataLine()
    private val sink = JvmAudioSink { line }
    private val spec = AudioSpec(sampleRate = 44_100, bitDepth = 16, channels = 1)

    @Test
    fun aStarvedButStartedLineStillReportsRunning() {
        sink.open(spec)
        sink.start()
        sink.write(ByteArray(2_048), 0, 2_048)

        // The mixer drains everything and JavaSound clears its own isRunning: presentation has ceased.
        line.drainQueued()
        line.running = false

        assertTrue(
            sink.isRunning,
            "a started line whose buffer momentarily emptied still reports a valid audible position; " +
                "reporting not-running here makes the display clock either overshoot or freeze"
        )

        // 1024 frames is 23ms of audio, so wait for it to have actually played before expecting to see
        // it. The position is derived from elapsed time (see JvmAudioSinkPositionTest), so asserting it
        // the instant after the write would be asserting that audio plays instantaneously.
        Thread.sleep(60)
        assertEquals(
            1_024L,
            sink.framePosition,
            "everything written has now had time to play, and the position is clamped to what was " +
                "written — so it lands exactly there rather than running on with the clock"
        )
    }

    @Test
    fun aStoppedLineReportsNotRunning() {
        sink.open(spec)
        sink.start()
        sink.write(ByteArray(2_048), 0, 2_048)

        sink.stop()

        assertFalse(
            sink.isRunning,
            "a stopped sink must report false: the player then falls back to the write cursor, which is " +
                "ahead of anything audible, and consumers need to know not to trust it"
        )
    }

    @Test
    fun aFreshlyOpenedLineReportsNotRunningUntilStarted() {
        sink.open(spec)

        assertFalse(sink.isRunning, "nothing has been started, so nothing is audible")

        sink.start()
        assertTrue(sink.isRunning, "started, even before the first buffer is written")
    }

    @Test
    fun aClosedLineReportsNotRunning() {
        sink.open(spec)
        sink.start()

        sink.close()

        assertFalse(sink.isRunning, "a closed sink has no position at all")
    }
}

/**
 * A [SourceDataLine] whose queue and `isRunning` can be driven independently, which is the whole point:
 * real JavaSound couples them in the way this test exists to stop mattering.
 *
 * [availableOverride] models a device that misreports its free space. Measured on a real machine: the
 * position derived from `bufferSize - available()` read 0 for three seconds of audible playback and then
 * leapt, which is what [JvmAudioSinkPositionTest] exists to prevent.
 */
internal class FakeSourceDataLine(
    private val availableOverride: Int? = null,
    private val bufferBytes: Int = 8_192
) : SourceDataLine {
    var running = false

    /** How many times the hardware line has actually been cycled — the expensive operation. */
    var opens = 0
        private set
    var closes = 0
        private set

    /** Buffer size the sink asked for, or -1 if it let the mixer choose (which measured 500ms). */
    var requestedBufferBytes = -1
        private set

    private var open = false
    private var queued = 0
    private var openedFormat: AudioFormat? = null

    fun drainQueued() {
        queued = 0
    }

    override fun open(format: AudioFormat, bufferSize: Int) {
        requestedBufferBytes = bufferSize
        openWith(format)
    }

    override fun open(format: AudioFormat) = openWith(format)

    override fun open() = openWith(AudioFormat(44_100f, 16, 1, true, false))

    private fun openWith(format: AudioFormat) {
        open = true
        openedFormat = format
        opens++
    }

    override fun write(b: ByteArray, off: Int, len: Int): Int {
        queued = minOf(bufferBytes, queued + len)
        running = true
        return len
    }

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun drain() {
        queued = 0
    }

    override fun flush() {
        queued = 0
    }

    override fun close() {
        open = false
        running = false
        queued = 0
        openedFormat = null
        closes++
    }

    override fun isOpen(): Boolean = open
    override fun isRunning(): Boolean = running
    override fun isActive(): Boolean = running
    override fun available(): Int = availableOverride ?: (bufferBytes - queued)
    override fun getBufferSize(): Int = bufferBytes
    override fun getFormat(): AudioFormat = openedFormat ?: AudioFormat(44_100f, 16, 1, true, false)
    override fun getFramePosition(): Int = 0
    override fun getLongFramePosition(): Long = 0
    override fun getMicrosecondPosition(): Long = 0
    override fun getLevel(): Float = 0f
    override fun getLineInfo(): javax.sound.sampled.Line.Info =
        javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format)
    override fun getControls(): Array<Control> = emptyArray()
    override fun isControlSupported(control: Control.Type): Boolean = false
    override fun getControl(control: Control.Type): Control = throw IllegalArgumentException("no controls")
    override fun addLineListener(listener: LineListener) = Unit
    override fun removeLineListener(listener: LineListener) = Unit
}
