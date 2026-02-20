package org.bibletranslationtools.bttrecorder2.ui.playback

import kotlin.math.min

/**
 * Tracks non-destructive cut operations over an original audio timeline.
 * All ranges are represented in absolute frame space, half-open: [start, end).
 */
class WaveEditSession(
    private val originalTotalFrames: Int
) {
    data class CutRange(val start: Int, val endExclusive: Int) {
        init {
            require(endExclusive >= start) { "Cut range end must be >= start" }
        }

        val length: Int
            get() = endExclusive - start

        fun contains(frame: Int): Boolean = frame in start until endExclusive
    }

    private var ranges: List<CutRange> = emptyList()
    private val undoStack = ArrayDeque<List<CutRange>>()
    private val redoStack = ArrayDeque<List<CutRange>>()

    val editedTotalFrames: Int
        get() = (originalTotalFrames - removedFrameCount()).coerceAtLeast(0)

    fun hasEdits(): Boolean = ranges.isNotEmpty()

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun rangesSnapshot(): List<CutRange> = ranges.toList()

    fun clearAllEdits() {
        if (!hasEdits()) return
        saveUndoSnapshot()
        ranges = emptyList()
        redoStack.clear()
    }

    fun cutRelative(startFrame: Int, endFrame: Int): Boolean {
        val a = min(startFrame, endFrame).coerceAtLeast(0)
        val b = maxOf(startFrame, endFrame).coerceAtLeast(0)
        if (b <= a) return false
        val absStart = relativeToAbsolute(a)
        val absEnd = relativeToAbsolute(b)
        return cutAbsolute(absStart, absEnd)
    }

    fun cutAbsolute(startFrame: Int, endFrame: Int): Boolean {
        val start = min(startFrame, endFrame).coerceIn(0, originalTotalFrames)
        val end = maxOf(startFrame, endFrame).coerceIn(0, originalTotalFrames)
        if (end <= start) return false

        saveUndoSnapshot()
        ranges = mergeRanges(ranges + CutRange(start, end))
        redoStack.clear()
        return true
    }

    fun undo(): Boolean {
        if (!canUndo()) return false
        redoStack.addLast(ranges.toList())
        ranges = undoStack.removeLast()
        return true
    }

    fun redo(): Boolean {
        if (!canRedo()) return false
        undoStack.addLast(ranges.toList())
        ranges = redoStack.removeLast()
        return true
    }

    fun isFrameRemoved(absoluteFrame: Int): Boolean {
        return ranges.any { it.contains(absoluteFrame) }
    }

    fun absoluteToRelative(absoluteFrame: Int): Int {
        val clamped = absoluteFrame.coerceIn(0, originalTotalFrames)
        return (clamped - removedFramesBeforeAbsolute(clamped)).coerceIn(0, editedTotalFrames)
    }

    fun relativeToAbsolute(relativeFrame: Int): Int {
        var absolute = relativeFrame.coerceIn(0, editedTotalFrames)
        var removedBefore = 0
        for (range in ranges) {
            val relativeRangeStart = range.start - removedBefore
            if (absolute < relativeRangeStart) {
                break
            }
            absolute += range.length
            removedBefore += range.length
        }
        return absolute.coerceIn(0, originalTotalFrames)
    }

    private fun removedFrameCount(): Int = ranges.sumOf { it.length }

    private fun removedFramesBeforeAbsolute(absoluteFrame: Int): Int {
        var removed = 0
        for (range in ranges) {
            if (absoluteFrame <= range.start) break
            val clippedEnd = min(absoluteFrame, range.endExclusive)
            if (clippedEnd > range.start) {
                removed += (clippedEnd - range.start)
            }
        }
        return removed
    }

    private fun saveUndoSnapshot() {
        undoStack.addLast(ranges.toList())
    }

    private fun mergeRanges(input: List<CutRange>): List<CutRange> {
        if (input.isEmpty()) return emptyList()
        val sorted = input.sortedBy { it.start }
        val out = mutableListOf<CutRange>()
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.start <= current.endExclusive) {
                current = CutRange(current.start, maxOf(current.endExclusive, next.endExclusive))
            } else {
                out.add(current)
                current = next
            }
        }
        out.add(current)
        return out
    }
}
