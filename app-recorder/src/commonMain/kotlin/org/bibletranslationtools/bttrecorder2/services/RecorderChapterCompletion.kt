package org.bibletranslationtools.bttrecorder2.services

import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.project.exporter.ChapterCompletion
import org.bibletranslationtools.otter.common.domain.project.exporter.ChapterStatus
import org.bibletranslationtools.otter.common.domain.project.exporter.selectedTakeHasMarkers

/**
 * The recorder records a chapter chunk by chunk, and export compiles the chunks into the chapter.
 * So a chapter is done when it has its own take, or when every chunk is recorded: every verse,
 * and the book and chapter titles.
 */
class RecorderChapterCompletion : ChapterCompletion {
    override fun status(workbook: Workbook, chapter: Chapter, mode: ProjectMode): ChapterStatus {
        if (chapter.hasSelectedAudio()) {
            return ChapterStatus(
                anyAudioRecorded = true,
                audioRecorded = true,
                verseMarkersPlaced = chapter.selectedTakeHasMarkers()
            )
        }
        val chunks = chapter.chunks.blockingGet()
        return ChapterStatus(
            anyAudioRecorded = chunks.any { it.hasSelectedAudio() },
            audioRecorded = chunks.isNotEmpty() && chunks.all { it.hasSelectedAudio() },
            // Compiling marks where each chunk starts.
            verseMarkersPlaced = true
        )
    }
}
