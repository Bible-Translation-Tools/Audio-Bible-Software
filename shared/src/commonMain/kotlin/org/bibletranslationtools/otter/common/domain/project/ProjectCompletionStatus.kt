package org.bibletranslationtools.otter.common.domain.project

import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.narration.ChapterRepresentation

class ProjectCompletionStatus() {

    /**
     * Whether a chapter counts as finished, asked the same way the home screen asks it.
     *
     * This used to be `chapter.hasSelectedAudio()` for every mode, which is only the TRANSLATION
     * answer: it looks for a selected take on the chapter, and that is what a translation project
     * produces. A NARRATION chapter's work lives in `active_verses.json` alongside the scratch
     * audio, and the compiled chapter take is a later, separate artifact — so a fully narrated
     * chapter whose take had not been compiled read as 0% here while
     * [org.bibletranslationtools.otter.common.persistence.repositories.WorkbookDescriptorRepository]
     * showed the book 100% complete on the home screen, because that one already branches on mode.
     *
     * The visible symptom was an export dialog that greyed out its own Export button for a finished
     * narration project: every chapter scored 0, so nothing was selectable. Two definitions of
     * "complete" for the same chapter is the actual defect; this makes the mode the only thing that
     * decides, and matches WorkbookDescriptorRepository.getProgress case for case.
     */
    fun isChapterComplete(workbook: Workbook, chapter: Chapter, mode: ProjectMode): Boolean =
        when (mode) {
            ProjectMode.NARRATION, ProjectMode.DIALECT ->
                getChapterNarrationProgress(workbook, chapter) == 1.0
            ProjectMode.TRANSLATION -> chapter.hasSelectedAudio()
        }

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