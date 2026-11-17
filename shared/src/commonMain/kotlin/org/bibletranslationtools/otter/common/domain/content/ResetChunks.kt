package org.bibletranslationtools.otter.common.domain.content

import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor

class ResetChunks {
    /**
     * Deletes the chapter's chunks, their takes' rows and then their audio, in one save. The old
     * chunks are retired: anything still holding one can no longer change it.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun resetChapter(accessor: ProjectFilesAccessor, chapter: Chapter): SaveResult<Unit> = chapter.resetChunks()
}
