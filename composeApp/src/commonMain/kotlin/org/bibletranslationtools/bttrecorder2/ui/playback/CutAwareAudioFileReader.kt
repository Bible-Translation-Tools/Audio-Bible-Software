package org.bibletranslationtools.bttrecorder2.ui.playback

import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import kotlin.math.min

/**
 * Audio reader that virtualizes cut ranges without rewriting the source file.
 * Exposed timeline is relative to the edited (cut-aware) frame space.
 */
class CutAwareAudioFileReader(
    private val delegate: AudioFileReader,
    cutRanges: List<WaveEditSession.CutRange>
) : AudioFileReader {

    private val ranges = cutRanges.sortedBy { it.start }
    private val removedFrames = ranges.sumOf { it.length }

    override val spec: AudioSpec
        get() = delegate.spec

    override val totalFrames: Int = (delegate.totalFrames - removedFrames).coerceAtLeast(0)

    override val framePosition: Int
        get() = absoluteToRelative(delegate.framePosition)

    private var scratch = ByteArray(DEFAULT_BUFFER_SIZE)

    override fun open() {
        delegate.open()
        skipIfInsideRemovedRange()
    }

    override fun hasRemaining(): Boolean {
        return framePosition < totalFrames
    }

    override fun getPcmBuffer(bytes: ByteArray): Int {
        if (bytes.isEmpty() || totalFrames == 0 || !hasRemaining()) return 0

        val frameBytes = spec.bytesPerFrame
        val requestedFrames = bytes.size / frameBytes
        if (requestedFrames <= 0) return 0

        var writtenBytes = 0
        while (writtenBytes < bytes.size && delegate.framePosition < delegate.totalFrames) {
            skipIfInsideRemovedRange()

            val absolutePos = delegate.framePosition
            if (absolutePos >= delegate.totalFrames) break

            val nextCutStart = nextCutStartAtOrAfter(absolutePos)
            val readableUntil = nextCutStart ?: delegate.totalFrames
            val readableFrames = (readableUntil - absolutePos).coerceAtLeast(0)
            if (readableFrames <= 0) break

            val remainingFrames = (bytes.size - writtenBytes) / frameBytes
            if (remainingFrames <= 0) break

            val framesToRead = min(readableFrames, remainingFrames)
            val bytesToRead = framesToRead * frameBytes

            if (scratch.size != bytesToRead) {
                scratch = ByteArray(bytesToRead)
            }

            val bytesRead = delegate.getPcmBuffer(scratch)
            if (bytesRead <= 0) break

            val alignedBytes = bytesRead - (bytesRead % frameBytes)
            if (alignedBytes <= 0) break

            System.arraycopy(scratch, 0, bytes, writtenBytes, alignedBytes)
            writtenBytes += alignedBytes
        }

        return writtenBytes
    }

    override fun seek(frame: Long) {
        val clamped = frame.toInt().coerceIn(0, totalFrames)
        val absoluteFrame = relativeToAbsolute(clamped)
        delegate.seek(absoluteFrame.toLong())
        skipIfInsideRemovedRange()
    }

    override fun release() {
        delegate.release()
    }

    private fun skipIfInsideRemovedRange() {
        while (true) {
            val absolute = delegate.framePosition
            val range = rangeContaining(absolute) ?: break
            delegate.seek(range.endExclusive.toLong())
        }
    }

    private fun rangeContaining(absoluteFrame: Int): WaveEditSession.CutRange? {
        return ranges.firstOrNull { absoluteFrame in it.start until it.endExclusive }
    }

    private fun nextCutStartAtOrAfter(absoluteFrame: Int): Int? {
        return ranges.firstOrNull { it.start >= absoluteFrame }?.start
    }

    private fun absoluteToRelative(absoluteFrame: Int): Int {
        var removedBefore = 0
        for (range in ranges) {
            if (absoluteFrame <= range.start) break
            val clippedEnd = min(absoluteFrame, range.endExclusive)
            if (clippedEnd > range.start) {
                removedBefore += (clippedEnd - range.start)
            }
        }
        return (absoluteFrame - removedBefore).coerceIn(0, totalFrames)
    }

    private fun relativeToAbsolute(relativeFrame: Int): Int {
        var absolute = relativeFrame
        var removedBefore = 0
        for (range in ranges) {
            val relativeRangeStart = range.start - removedBefore
            if (absolute < relativeRangeStart) break
            absolute += range.length
            removedBefore += range.length
        }
        return absolute.coerceAtLeast(0)
    }
}
