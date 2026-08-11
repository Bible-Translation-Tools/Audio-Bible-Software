package org.bibletranslationtools.otter.common.device

import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Where a `play()` starts from, which is the difference between replaying a finished take and
 * reporting a phantom completion.
 *
 * [AudioPlayerConnectionFactory.connect] does `load(reader)` then `seek(position)`, so the position
 * handed to it decides what state [AudioBufferPlayer.play] finds the reader in. Connecting at a
 * finished take's end position leaves nothing remaining: the playback loop breaks on its first
 * iteration without reading, then still drains and emits `Complete`. The worker reports Play followed
 * immediately by Complete with no audio, and the UI shows a jump to the end with the transport flipped
 * back to paused.
 *
 * The connection used to connect first and only then probe the worker's position to decide about
 * rewinding. That probe reads through `coerceAtMost(lastKnownLocationInFrames)` and the playback-rate
 * scaling, so it sometimes reported just under `totalFrames` and skipped the rewind — which is why the
 * failure was intermittent. The rewind decision is now made from `lastPosition` before connecting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlayerConnectionReplayTest {

    private val totalFrames = 44_100 * 5

    private val factory: AudioPlayerConnectionFactory = mockk(relaxed = true)
    private val reader: AudioFileReader = mockk(relaxed = true) {
        every { this@mockk.totalFrames } returns this@AudioPlayerConnectionReplayTest.totalFrames
    }

    /** The worker reports the end, as it does once a take has played through. */
    private fun workerAtEnd() {
        every { factory.getPlayerWorker() } returns mockk(relaxed = true) {
            every { getLocationInFrames() } returns totalFrames.toLong()
        }
    }

    /** The worker reports just under the end — the read that used to skip the rewind. */
    private fun workerJustUnderEnd() {
        every { factory.getPlayerWorker() } returns mockk(relaxed = true) {
            every { getLocationInFrames() } returns totalFrames.toLong() - 1
        }
    }

    /**
     * Loads [reader], leaves the position at [position], and forgets the resulting calls — `load()`
     * itself connects at 0, which would otherwise be indistinguishable from a rewind.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.connectionAt(
        position: Int
    ): AudioPlayerConnection {
        val connection = AudioPlayerConnection(id = 1, factory = factory, scope = this)
        connection.load(reader)
        if (position != 0) connection.seek(position)
        advanceUntilIdle()
        clearMocks(factory, answers = false, recordedCalls = true, childMocks = false)
        return connection
    }

    @Test
    fun `replaying a finished take connects at the start`() = runTest {
        workerAtEnd()
        val connection = connectionAt(totalFrames) // where playback left the position

        connection.play()
        advanceUntilIdle()

        coVerify { factory.connect(1, reader, 0L) }
    }

    /**
     * The case that made this intermittent. Even when the worker's own position reads short of the
     * end — so the post-connect rewind would not fire — the connection must not connect at the end.
     */
    @Test
    fun `replaying connects at the start even when the worker reports just under the end`() = runTest {
        workerJustUnderEnd()
        val connection = connectionAt(totalFrames)

        connection.play()
        advanceUntilIdle()

        coVerify { factory.connect(1, reader, 0L) }
        coVerify(exactly = 0) { factory.connect(1, reader, totalFrames.toLong()) }
    }

    /** A position past the end (a clamped or over-reported seek) is a finished take too. */
    @Test
    fun `a position past the end also rewinds`() = runTest {
        workerAtEnd()
        val connection = connectionAt(totalFrames + 500)

        connection.play()
        advanceUntilIdle()

        coVerify { factory.connect(1, reader, 0L) }
    }

    // ── resuming mid-take must not rewind ────────────────────────────────────────────────

    @Test
    fun `resuming mid-take connects at the paused position`() = runTest {
        val midpoint = totalFrames / 2
        every { factory.getPlayerWorker() } returns mockk(relaxed = true) {
            every { getLocationInFrames() } returns midpoint.toLong()
        }
        val connection = connectionAt(midpoint)

        connection.play()
        advanceUntilIdle()

        coVerify { factory.connect(1, reader, midpoint.toLong()) }
        coVerify(exactly = 0) { factory.connect(1, reader, 0L) }
    }

    @Test
    fun `a freshly loaded take plays from the start`() = runTest {
        every { factory.getPlayerWorker() } returns mockk(relaxed = true) {
            every { getLocationInFrames() } returns 0L
        }
        val connection = connectionAt(0)

        connection.play()
        advanceUntilIdle()

        coVerify { factory.connect(1, reader, 0L) }
    }
}
