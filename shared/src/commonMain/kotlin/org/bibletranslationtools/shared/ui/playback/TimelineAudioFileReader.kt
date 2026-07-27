package org.bibletranslationtools.shared.ui.playback

import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import kotlin.math.min

/**
 * The one general reader over an [AudioTimeline] (generalizes the old
 * CutAwareAudioFileReader and mirrors ChapterRepresentationConnection): it plays back
 * the logical timeline by hopping between the underlying source readers segment by
 * segment. [framePosition] reports the TIMELINE frame.
 *
 * One raw reader is kept open per distinct [PcmSource.id]; all are released on
 * [release]. The exposed [spec] comes from the first segment's source (empty timeline
 * falls back to a default spec).
 */
class TimelineAudioFileReader(
    val timeline: AudioTimeline
) : AudioFileReader {

    override val totalFrames: Int = timeline.totalFrames

    // One open reader per distinct source id (lazy).
    private val openReaders = HashMap<String, AudioFileReader>()

    // Current position in TIMELINE space.
    private var timelinePosition: Int = 0

    private var scratch = ByteArray(DEFAULT_BUFFER_SIZE)

    override val spec: AudioSpec by lazy {
        val source = timeline.segments.firstOrNull()?.source
        if (source != null) {
            readerFor(source).spec
        } else {
            AudioSpec(sampleRate = 44100, bitDepth = 16, channels = 1)
        }
    }

    override val framePosition: Int
        get() = timelinePosition

    private fun readerFor(source: PcmSource): AudioFileReader {
        return openReaders.getOrPut(source.id) {
            source.openReader().also { it.open() }
        }
    }

    override fun open() {
        // Position the underlying reader(s) at the current timeline frame.
        seek(timelinePosition.toLong())
    }

    override fun hasRemaining(): Boolean {
        return timelinePosition < totalFrames
    }

    override fun getPcmBuffer(bytes: ByteArray): Int {
        if (bytes.isEmpty() || totalFrames == 0 || !hasRemaining()) return 0

        val frameBytes = spec.bytesPerFrame
        val requestedFrames = bytes.size / frameBytes
        if (requestedFrames <= 0) return 0

        var writtenBytes = 0
        while (writtenBytes < bytes.size && timelinePosition < totalFrames) {
            val (segIndex, sourceFrame) = timeline.mapToSource(timelinePosition)
            val seg = timeline.segments[segIndex]
            val reader = readerFor(seg.source)

            // Align the source reader to where this timeline frame maps.
            if (reader.framePosition != sourceFrame) {
                reader.seek(sourceFrame.toLong())
            }

            // Frames readable from this segment before we must hop to the next.
            val framesLeftInSegment = seg.sourceFrames.last + 1 - sourceFrame
            if (framesLeftInSegment <= 0) break

            val remainingFrames = (bytes.size - writtenBytes) / frameBytes
            if (remainingFrames <= 0) break

            val framesToRead = min(framesLeftInSegment, remainingFrames)
            val bytesToRead = framesToRead * frameBytes

            if (scratch.size != bytesToRead) {
                scratch = ByteArray(bytesToRead)
            }

            val bytesRead = reader.getPcmBuffer(scratch)
            if (bytesRead <= 0) break

            val alignedBytes = bytesRead - (bytesRead % frameBytes)
            if (alignedBytes <= 0) break

            System.arraycopy(scratch, 0, bytes, writtenBytes, alignedBytes)
            writtenBytes += alignedBytes
            timelinePosition += alignedBytes / frameBytes
        }

        return writtenBytes
    }

    override fun seek(frame: Long) {
        val clamped = frame.toInt().coerceIn(0, totalFrames)
        timelinePosition = clamped
        if (clamped >= totalFrames || timeline.segments.isEmpty()) return
        val (segIndex, sourceFrame) = timeline.mapToSource(clamped)
        val seg = timeline.segments[segIndex]
        readerFor(seg.source).seek(sourceFrame.toLong())
    }

    override fun release() {
        openReaders.values.forEach { it.release() }
        openReaders.clear()
    }
}
