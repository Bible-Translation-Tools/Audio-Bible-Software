package org.bibletranslationtools.bttrecorder2.ui.playback

import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import java.io.File

/**
 * A source of PCM audio addressable by absolute frame. Today: one WAV file (a take).
 * Later: narration scratch tape, live-recording ring buffer.
 */
interface PcmSource {
    /** Stable key for peak-cache lookup and reader de-duplication (e.g. file path). */
    val id: String
    val totalFrames: Int
    val sampleRate: Int

    /** Opens a fresh raw reader over the WHOLE source. */
    fun openReader(): AudioFileReader
}

/**
 * A [PcmSource] backed by an audio file on disk. The wrapped [OratureAudioFile] is
 * created lazily and cached so [totalFrames]/[sampleRate] are read once; every
 * [openReader] call still returns an independent reader over the whole file.
 */
class FilePcmSource(val file: File) : PcmSource {
    override val id: String = file.absolutePath

    private val audioFile: OratureAudioFile by lazy { OratureAudioFile(file) }

    override val totalFrames: Int
        get() = audioFile.totalFrames

    override val sampleRate: Int
        get() = audioFile.sampleRate

    override fun openReader(): AudioFileReader = OratureAudioFile(file).reader()
}

/**
 * One contiguous run of source audio on the logical timeline. [sourceFrames] is a
 * half-open range expressed as an inclusive [IntRange] (last == endExclusive - 1).
 */
data class Segment(val source: PcmSource, val sourceFrames: IntRange)

/**
 * Immutable logical timeline: an ordered list of [Segment]s. THE shared model for the
 * player + renderer (+ future narration/recording adapters — mirrors
 * ChapterRepresentation's VerseNode sectors). Editing primitives return NEW timelines.
 */
class AudioTimeline(val segments: List<Segment>) {

    val totalFrames: Int = segments.sumOf { it.sourceFrames.count() }

    /**
     * Prefix sums of segment lengths: [segStarts][i] is the timeline frame at which
     * segment i begins. Length == segments.size (last segment's start; not size+1).
     */
    private val segStarts: IntArray = IntArray(segments.size).also { starts ->
        var acc = 0
        for (i in segments.indices) {
            starts[i] = acc
            acc += segments[i].sourceFrames.count()
        }
    }

    /** Timeline frame at which segment [index] begins. */
    fun segmentStartFrame(index: Int): Int = segStarts[index]

    /**
     * Maps a timeline frame to (segmentIndex, sourceFrame). O(log n). Clamps [frame]
     * into [0, totalFrames). For an empty timeline or a frame at/after the end, returns
     * the last valid position (or (0, 0) when empty).
     */
    fun mapToSource(frame: Int): Pair<Int, Int> {
        if (segments.isEmpty()) return 0 to 0
        val clamped = frame.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        // Binary search for the last segment whose start <= clamped.
        var lo = 0
        var hi = segStarts.size - 1
        var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segStarts[mid] <= clamped) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val seg = segments[idx]
        val offset = clamped - segStarts[idx]
        return idx to (seg.sourceFrames.first + offset)
    }

    /**
     * Removes the timeline frames [fromFrame, toFrameExclusive) by splitting the
     * segments they touch and dropping the covered portions. Returns a NEW timeline.
     * Inputs are clamped to [0, totalFrames]; an empty or reversed range is a no-op
     * (returns a timeline with equivalent content).
     */
    fun cut(fromFrame: Int, toFrameExclusive: Int): AudioTimeline {
        val from = fromFrame.coerceIn(0, totalFrames)
        val to = toFrameExclusive.coerceIn(0, totalFrames)
        if (to <= from) return AudioTimeline(segments.toList())

        val out = ArrayList<Segment>(segments.size + 1)
        // segStart tracks the timeline start of the current segment as we walk.
        for (i in segments.indices) {
            val seg = segments[i]
            val segStart = segStarts[i]
            val segLen = seg.sourceFrames.count()
            val segEnd = segStart + segLen // exclusive, timeline space

            if (segEnd <= from || segStart >= to) {
                // Fully outside the cut range: keep as-is.
                out.add(seg)
                continue
            }

            // Portion before the cut (timeline [segStart, from)).
            if (segStart < from) {
                val keepFrames = from - segStart
                val srcStart = seg.sourceFrames.first
                out.add(Segment(seg.source, srcStart until (srcStart + keepFrames)))
            }
            // Portion after the cut (timeline [to, segEnd)).
            if (segEnd > to) {
                val dropFromStart = to - segStart
                val srcStart = seg.sourceFrames.first + dropFromStart
                out.add(Segment(seg.source, srcStart..seg.sourceFrames.last))
            }
        }
        return AudioTimeline(out)
    }

    companion object {
        fun ofWholeSource(source: PcmSource): AudioTimeline =
            AudioTimeline(
                if (source.totalFrames <= 0) emptyList()
                else listOf(Segment(source, 0 until source.totalFrames))
            )
    }
}
