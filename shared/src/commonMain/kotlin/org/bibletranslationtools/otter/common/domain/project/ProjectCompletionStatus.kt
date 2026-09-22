package org.bibletranslationtools.otter.common.domain.project

import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.narration.ChapterRepresentation

class ProjectCompletionStatus() {

    fun getChapterNarrationProgress(workbook: Workbook, chapter: Chapter): Double {
        val chapterRepresentation = ChapterRepresentation(workbook, chapter)
        chapterRepresentation.loadFromSerializedVerses()
        val fileProgress = chapterRepresentation.getCompletionProgress()
        if (fileProgress >= 1.0) return fileProgress

        // An imported (never-opened) completed narration chapter has an empty active_verses.json but a
        // selected chapter take whose embedded audio markers are the only surviving verse markers (import
        // inserts takes with no DB markers). Derive completion from that take's marker coverage so the home
        // ring and export selectability are correct before the chapter is ever opened. A take that is
        // MISSING markers scores below 1.0 and is NOT treated as complete.
        val takeFile = chapter.getSelectedTake()?.file?.takeIf { it.exists() } ?: return fileProgress
        return maxOf(fileProgress, chapterRepresentation.getCompletionProgressFromTake(takeFile))
    }

    fun getChapterTranslationProgress(chapter: Chapter): Double {
        val chunkCount = chapter.chunkCount.blockingGet()

        if (chunkCount == 0) return 0.0

        val chunkWithAudio = chapter.chunks
            .map {
                it.count { it.hasSelectedAudio() }
            }
            .blockingGet()

        return chunkWithAudio.toDouble() / chunkCount
    }
}