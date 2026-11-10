package org.bibletranslationtools.orature.services

import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.project.ProjectCompletionStatus
import org.bibletranslationtools.otter.common.domain.project.exporter.ChapterCompletion
import org.bibletranslationtools.otter.common.domain.project.exporter.ChapterStatus
import org.bibletranslationtools.otter.common.domain.project.exporter.selectedTakeHasMarkers

/** An Orature chapter is done when its project mode says so (see [ProjectCompletionStatus.isChapterComplete]). */
class OratureChapterCompletion(
    private val completionStatus: ProjectCompletionStatus
) : ChapterCompletion {
    override fun status(workbook: Workbook, chapter: Chapter, mode: ProjectMode): ChapterStatus {
        val complete = completionStatus.isChapterComplete(workbook, chapter, mode)
        return ChapterStatus(
            // Orature tracks chapters as done or not, so a backup also takes only done ones.
            anyAudioRecorded = complete,
            audioRecorded = complete,
            verseMarkersPlaced = chapter.selectedTakeHasMarkers()
        )
    }
}
