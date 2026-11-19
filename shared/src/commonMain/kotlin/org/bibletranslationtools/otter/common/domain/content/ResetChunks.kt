/**
 * Copyright (C) 2020-2024 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.domain.content

import org.bibletranslationtools.otter.common.data.Chunkification
import java.io.File
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import org.bibletranslationtools.otter.common.OTTER_JSON
import org.bibletranslationtools.otter.common.data.CHUNKIFICATION

class ResetChunks() {
    /** Deletes the chapter's chunks and their takes' rows in one transaction, then their audio. */
    suspend fun resetChapter(accessor: ProjectFilesAccessor, chapter: Chapter): SaveResult<Unit> {
        return chapter.resetChunks()
    }

    private fun deleteChunkedSourceAudio(accessor: ProjectFilesAccessor, chapter: Chapter) {
        accessor.sourceAudioDir.listFiles()?.forEach {  file ->
            if(chapterMatches(file, chapter.sort)) {
                file.delete()
            }
        }
    }

    private fun chapterMatches(file: File, chapter: Int): Boolean {
        return file.name.contains(Regex("_c(0*)$chapter\\."))
    }

    private fun removeChapterFromChunkFile(chunkFile: File, chapterNumber: Int) {
        if (!chunkFile.exists() || chunkFile.length() == 0L) {
            return
        }

        val chunks: Chunkification = OTTER_JSON.decodeFromString(CHUNKIFICATION, chunkFile.readText())
        chunks.remove(chapterNumber)

        chunkFile.writer().use {
            it.write(OTTER_JSON.encodeToString(CHUNKIFICATION, chunks))
        }
    }
}
