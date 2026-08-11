package org.bibletranslationtools.otter.common.device

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shutdown net: what it releases, and — the part that actually matters — that one uncooperative
 * line cannot stop it releasing the rest.
 *
 * A shutdown hook gets no second chance and has no one to report to. If it throws part-way through, the
 * lines after the throwing one stay claimed and the exit itself may report a failure, which is a worse
 * outcome than the leak it exists to insure against. Real devices do throw on the way out (unplugged
 * mid-session, driver already torn down), and shutdown is exactly when that is most likely.
 *
 * Driven through [JvmAudioLines.closeOpenLines] with supplied lines rather than the real mixers: a test
 * that had to open the microphone to check this would be a poor trade.
 */
class JvmAudioLinesTest {

    private val format = AudioFormat(44_100f, 16, 1, true, false)

    @Test
    fun everyOpenLineIsReleased() {
        val lines = List(3) { FakeSourceDataLine().also { it.open(format) } }

        val closed = JvmAudioLines.closeOpenLines { lines }

        assertEquals(3, closed, "all three were open, so all three had to be released")
        assertTrue(lines.none { it.isOpen }, "and none may be left holding its device")
    }

    @Test
    fun aRunningLineIsStoppedBeforeItIsClosed() {
        val underlying = FakeSourceDataLine().also { it.open(format) }
        underlying.start()
        val line = RecordingLine(underlying)
        assertTrue(line.isRunning, "precondition: the line is running")

        JvmAudioLines.closeOpenLines { listOf(line) }

        // Order, not just outcome: closing a capture line that is still running is the operation
        // known to hang, and a hung shutdown hook hangs the exit.
        assertEquals(listOf("stop", "flush", "close"), line.calls)
        assertFalse(underlying.isOpen, "a running line is the likeliest leak, so it is still released")
    }

    @Test
    fun aLineThatThrowsOnCloseDoesNotStrandTheOnesBehindIt() {
        val first = FakeSourceDataLine().also { it.open(format) }
        val hostile = ThrowingOnClose(FakeSourceDataLine().also { it.open(format) })
        val last = FakeSourceDataLine().also { it.open(format) }

        val closed = JvmAudioLines.closeOpenLines { listOf(first, hostile, last) }

        assertEquals(2, closed, "the throwing line is not counted as released, because it was not")
        assertFalse(first.isOpen, "the line before it is released")
        assertFalse(
            last.isOpen,
            "and so is the line AFTER it — one device that throws on the way out must not leave every " +
                "line behind it claimed"
        )
    }

    @Test
    fun linesThatAreAlreadyClosedAreLeftAlone() {
        val untouched = FakeSourceDataLine()

        val closed = JvmAudioLines.closeOpenLines { listOf(untouched) }

        assertEquals(0, closed, "nothing was open, so nothing was released")
        assertEquals(0, untouched.closes, "and a closed line must not be closed again for the count")
    }

    @Test
    fun aFailureToEnumerateLinesIsNotFatal() {
        // `AudioSystem.getMixerInfo()` and friends do throw on a machine whose audio stack is in a bad
        // way. At shutdown there is nothing useful to do about it except exit cleanly.
        val closed = JvmAudioLines.closeOpenLines { error("audio system unavailable") }

        assertEquals(0, closed, "an unenumerable audio system releases nothing and throws nothing")
    }
}

/** A line whose device has already gone away by the time it is asked to close. */
private class ThrowingOnClose(
    delegate: SourceDataLine
) : SourceDataLine by delegate {
    override fun close(): Unit = throw IllegalStateException("device already gone")
}

/** Records the release sequence, so the order can be asserted rather than only the end state. */
private class RecordingLine(
    private val delegate: SourceDataLine
) : SourceDataLine by delegate {
    val calls = mutableListOf<String>()

    override fun stop() {
        calls += "stop"
        delegate.stop()
    }

    override fun flush() {
        calls += "flush"
        delegate.flush()
    }

    override fun close() {
        calls += "close"
        delegate.close()
    }
}
