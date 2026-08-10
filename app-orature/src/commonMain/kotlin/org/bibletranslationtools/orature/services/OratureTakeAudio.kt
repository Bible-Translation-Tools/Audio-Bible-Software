package org.bibletranslationtools.orature.services

import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.shared.audio.engine.AudioTimeline
import org.bibletranslationtools.shared.audio.engine.FilePcmSource

/**
 * The workbook → playback-engine bridge: turns a recorded [Take] (or a recordable's selected
 * take) into an [AudioTimeline] over the shared engine's [FilePcmSource]. This is the
 * contract Phases 5+ (narration/translation playback) build on — the same path the recorder's
 * PlaybackViewModel uses (`AudioTimeline.ofWholeSource(FilePcmSource(take.file))`), kept as
 * one small Orature-side adapter so the workbook layer never touches the engine directly.
 */
object OratureTakeAudio {

    /** A single-segment timeline spanning the whole take file. */
    fun timelineFor(take: Take): AudioTimeline =
        AudioTimeline.ofWholeSource(FilePcmSource(take.file))

    /**
     * The timeline for [audio]'s selected take, or null when nothing is selected or the
     * selection is soft-deleted (JVM: takes with a `deletedTimestamp` are not playable).
     */
    fun timelineForSelected(audio: AssociatedAudio): AudioTimeline? =
        audio.getSelectedTake()
            ?.takeUnless { it.isDeleted() }
            ?.let(::timelineFor)
}
