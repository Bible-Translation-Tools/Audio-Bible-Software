package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioBufferPlayerTest {

    @Test
    fun testPlaybackLifecycle() = runTest {
        val sink = MockAudioSink()
        val processor = IdentityAudioProcessor()
        val player = AudioBufferPlayer(sink, processor, this)

        // Assuming a MockReader that provides 1024 bytes
        val mockReader = MockAudioFileReader(totalFrames = 512)

        player.load(mockReader)
        assertTrue(sink.isOpen, "Sink should be open after load")

        player.play()
        // Allow the coroutine to churn
        testScheduler.advanceUntilIdle()

        assertTrue(sink.bytesWritten > 0, "Sink should have received data")
        player.release()
    }

    // Sink never reports progress (framePosition stuck at 0) even though it claims to be
    // running. Per the current contract, getLocationInFrames() trusts the sink while it is
    // running (sessionStartFrame + sink.framePosition, capped by the write cursor), so a
    // stuck-at-zero sink correctly pins the reported location at 0 -- callers are expected
    // to notice this via isPositionReliable rather than getLocationInFrames() silently
    // substituting the reader's position.
    @Test
    fun testLocationStaysAtSinkPositionWhenSinkPositionStaysZero() = runTest {
        val sink = object : AudioSink {
            override val isRunning: Boolean
                get() = true
            override val framePosition: Long
                get() = 0L

            override fun open(spec: AudioSpec) = Unit
            override fun start() = Unit
            override fun write(data: ByteArray, offset: Int, size: Int): Int = size
            override fun stop() = Unit
            override fun drain() = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }

        val processor = IdentityAudioProcessor()
        val player = AudioBufferPlayer(sink, processor, this)
        val reader = MockAudioFileReader(totalFrames = 4_096)

        player.load(reader)
        player.play()
        testScheduler.advanceUntilIdle()

        assertEquals(0L, player.getLocationInFrames(), "While running, location should track the sink's own frame position")
        player.release()
    }

    // Sink reports a fixed, stale position while running. Same contract as above: the
    // running sink's framePosition is authoritative, so the reported location tracks it
    // (capped by the write cursor) rather than falling back to the reader's position.
    @Test
    fun testLocationTracksFramesPlayedSinceSessionStart() = runTest {
        // The sink's counter starts this session at a non-zero value (didn't reset — the resume
        // condition). The reported location must reflect frames played SINCE play() began
        // (framePosition - session baseline), NOT the sink's raw absolute counter, which would add
        // the session anchor twice and make the position jump ahead.
        val sink = object : AudioSink {
            override val isRunning: Boolean get() = true
            override var framePosition: Long = 100L // leftover from a prior session
            override fun open(spec: AudioSpec) = Unit
            override fun start() = Unit
            override fun write(data: ByteArray, offset: Int, size: Int): Int = size
            override fun stop() = Unit
            override fun drain() = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }

        val processor = IdentityAudioProcessor()
        val player = AudioBufferPlayer(sink, processor, this)
        val reader = MockAudioFileReader(totalFrames = 4_096)

        player.load(reader)
        player.play()
        testScheduler.advanceUntilIdle()

        // 50 frames play this session (100 -> 150). Location = sessionStart(0) + played(50) = 50,
        // NOT the raw counter 150 (which would be the anchor double-counted).
        sink.framePosition = 150L
        assertEquals(50L, player.getLocationInFrames(), "location must track frames played since the session started, not the sink's raw counter")
        player.release()
    }

    // While the sink is not running, getLocationInFrames() falls back to the write cursor
    // (lastKnownLocationInFrames), which IS driven by the reader as data is written -- and
    // isPositionReliable surfaces that this fallback is in effect.
    @Test
    fun testLocationUsesWriteCursorWhenSinkNotRunning() = runTest {
        val sink = object : AudioSink {
            override val isRunning: Boolean
                get() = false
            override val framePosition: Long
                get() = 0L

            override fun open(spec: AudioSpec) = Unit
            override fun start() = Unit
            override fun write(data: ByteArray, offset: Int, size: Int): Int = size
            override fun stop() = Unit
            override fun drain() = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }

        val processor = IdentityAudioProcessor()
        val player = AudioBufferPlayer(sink, processor, this)
        val reader = MockAudioFileReader(totalFrames = 4_096)

        player.load(reader)
        player.play()
        testScheduler.advanceUntilIdle()

        assertFalse(player.isPositionReliable, "isPositionReliable should be false while the sink is not running")
        assertTrue(player.getLocationInFrames() > 0, "Location should advance via the write cursor even though the sink never reports running")
        player.release()
    }

    /**
     * Resume double-count guard. On some platforms the sink's frame counter does NOT reset to 0 when
     * a play session resumes (it still carries the previous session's frames). getLocationInFrames()
     * must report sessionStart + frames-played-THIS-session, NOT sessionStart + the sink's absolute
     * counter — otherwise the resume anchor is added twice and the position (and the waveform) jump
     * ahead by the pause point, compounding every pause/resume cycle (the measured narration bug:
     * loc ≈ 2× the real position after resume).
     *
     * The `rate != 1.0` position branch is used so the read is exercised WITHOUT the write-cursor
     * cap masking the doubling; the baseline subtraction it verifies is identical in the 1.0× branch.
     */
    @Test
    fun resumeDoesNotDoubleCountWhenSinkCounterDoesNotReset() = runTest {
        val sink = NonResettingSink()
        val processor = IdentityAudioProcessor().apply { setPlaybackRate(2.0) }
        val player = AudioBufferPlayer(sink, processor, this)
        val reader = MockAudioFileReader(totalFrames = 0) // empty → play loop is a no-op; we drive position manually

        // Simulate a resume: the sink still holds 1000 frames from the prior session (never reset),
        // and playback resumes at frame 1000.
        sink.framePosition = 1000L
        player.load(reader)
        player.seek(1000)
        player.play()
        testScheduler.advanceUntilIdle()

        // 200 more frames play THIS session.
        sink.framePosition = 1200L

        // Correct: sessionStart(1000) + playedThisSession(1200-1000=200) * rate(2.0) = 1400.
        // Without the baseline fix it would be sessionStart(1000) + sinkAbsolute(1200)*2.0 = 3400.
        assertEquals(1400L, player.getLocationInFrames(), "resume must not double-count the session anchor")
        player.release()
    }
}

/** A sink whose frame counter accumulates across sessions and is NOT reset by open/start/flush/stop —
 *  reproducing the platform behavior that made narration's position double after a resume. isRunning
 *  stays true after start() so the running position path can be read deterministically. */
private class NonResettingSink : org.bibletranslationtools.otter.common.device.newaudio.AudioSink {
    override var framePosition: Long = 0
    override var isRunning: Boolean = false
    override fun open(spec: org.bibletranslationtools.otter.common.device.newaudio.AudioSpec) = Unit
    override fun start() { isRunning = true }
    override fun write(data: ByteArray, offset: Int, size: Int): Int = size
    override fun stop() { /* deliberately does NOT reset framePosition or isRunning */ }
    override fun drain() = Unit
    override fun flush() { /* deliberately does NOT reset framePosition */ }
    override fun close() = Unit
}
