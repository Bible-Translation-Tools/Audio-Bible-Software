package org.bibletranslationtools.shared.ui.playback

import kotlin.math.min

/**
 * Tracks non-destructive edits over a single [PcmSource] as an [AudioTimeline] of
 * surviving segments. Cuts split/drop segments; undo/redo swap immutable timeline
 * snapshots. Frame math is expressed in the source's absolute frame space (the "source"
 * / "absolute" frames) and the edited timeline space (the "relative" frames).
 *
 * The public API is preserved from the original cut-range implementation so callers
 * (PlaybackViewModel marker remapping, existing tests) keep working; [CutRange] and
 * [rangesSnapshot] are derived from the current timeline (the complement of surviving
 * segments over the source).
 */
class WaveEditSession(
    private val source: PcmSource
) {
    private val originalTotalFrames: Int = source.totalFrames

    data class CutRange(val start: Int, val endExclusive: Int) {
        init {
            require(endExclusive >= start) { "Cut range end must be >= start" }
        }

        val length: Int
            get() = endExclusive - start

        fun contains(frame: Int): Boolean = frame in start until endExclusive
    }

    private var current: AudioTimeline = AudioTimeline.ofWholeSource(source)
    private val undoStack = ArrayDeque<AudioTimeline>()
    private val redoStack = ArrayDeque<AudioTimeline>()

    val editedTotalFrames: Int
        get() = current.totalFrames

    fun timeline(): AudioTimeline = current

    fun hasEdits(): Boolean = current.totalFrames != originalTotalFrames

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /** Removed ranges in source-absolute space (complement of surviving segments). */
    fun rangesSnapshot(): List<CutRange> {
        val out = mutableListOf<CutRange>()
        var cursor = 0
        for (seg in current.segments) {
            val segStart = seg.sourceFrames.first
            if (segStart > cursor) out.add(CutRange(cursor, segStart))
            cursor = seg.sourceFrames.last + 1
        }
        if (cursor < originalTotalFrames) out.add(CutRange(cursor, originalTotalFrames))
        return out
    }

    fun clearAllEdits() {
        if (!hasEdits()) return
        saveUndoSnapshot()
        current = AudioTimeline.ofWholeSource(source)
        redoStack.clear()
    }

    fun cutRelative(startFrame: Int, endFrame: Int): Boolean {
        val a = min(startFrame, endFrame).coerceAtLeast(0)
        val b = maxOf(startFrame, endFrame).coerceAtLeast(0)
        if (b <= a) return false

        val before = current.totalFrames
        saveUndoSnapshot()
        current = current.cut(a, b)
        if (current.totalFrames == before) {
            // No-op cut: revert the speculative snapshot to keep undo history clean.
            undoStack.removeLast()
            return false
        }
        redoStack.clear()
        return true
    }

    fun cutAbsolute(startFrame: Int, endFrame: Int): Boolean {
        val absStart = min(startFrame, endFrame).coerceIn(0, originalTotalFrames)
        val absEnd = maxOf(startFrame, endFrame).coerceIn(0, originalTotalFrames)
        if (absEnd <= absStart) return false

        // Remove the SURVIVING source frames whose source position falls in
        // [absStart, absEnd). Translating that source window into timeline space and
        // cutting it reproduces the old cut-range semantics (overlapping absolute cuts
        // merge; frames already removed are simply not re-removed).
        val relStart = absoluteToRelative(absStart)
        val relEnd = absoluteToRelative(absEnd)
        if (relEnd <= relStart) return false

        val before = current.totalFrames
        saveUndoSnapshot()
        current = current.cut(relStart, relEnd)
        if (current.totalFrames == before) {
            undoStack.removeLast()
            return false
        }
        redoStack.clear()
        return true
    }

    fun undo(): Boolean {
        if (!canUndo()) return false
        redoStack.addLast(current)
        current = undoStack.removeLast()
        return true
    }

    fun redo(): Boolean {
        if (!canRedo()) return false
        undoStack.addLast(current)
        current = redoStack.removeLast()
        return true
    }

    /** True iff no surviving segment contains the given source frame. */
    fun isFrameRemoved(absoluteFrame: Int): Boolean {
        if (absoluteFrame < 0 || absoluteFrame >= originalTotalFrames) return false
        return current.segments.none { absoluteFrame in it.sourceFrames }
    }

    /**
     * Timeline (edited) position of the given source frame: the count of surviving
     * frames that lie before it. Matches the old cut-range semantics exactly.
     */
    fun absoluteToRelative(absoluteFrame: Int): Int {
        val clamped = absoluteFrame.coerceIn(0, originalTotalFrames)
        var relative = 0
        for (seg in current.segments) {
            val segStart = seg.sourceFrames.first
            val segEndExclusive = seg.sourceFrames.last + 1
            when {
                clamped >= segEndExclusive -> relative += seg.sourceFrames.count()
                clamped <= segStart -> return relative.coerceIn(0, editedTotalFrames)
                else -> return (relative + (clamped - segStart)).coerceIn(0, editedTotalFrames)
            }
        }
        return relative.coerceIn(0, editedTotalFrames)
    }

    /** Source-absolute frame for a given timeline (edited) position. */
    fun relativeToAbsolute(relativeFrame: Int): Int {
        val clamped = relativeFrame.coerceIn(0, editedTotalFrames)
        var consumed = 0
        for (seg in current.segments) {
            val segLen = seg.sourceFrames.count()
            if (clamped < consumed + segLen) {
                return (seg.sourceFrames.first + (clamped - consumed)).coerceIn(0, originalTotalFrames)
            }
            consumed += segLen
        }
        return originalTotalFrames
    }

    private fun saveUndoSnapshot() {
        undoStack.addLast(current)
    }
}
