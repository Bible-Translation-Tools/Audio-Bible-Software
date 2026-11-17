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

import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.NewTake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesService
import java.io.File

/**
 * Registers an existing audio file as a new take of [recordable], and selects it.
 *
 * Both apps had their own copy of this sequence — allocate the next take number, build the
 * project's file name for it, create the take in the chapter's audio directory, copy the audio
 * in, then insert. The recorder used it to save waveform edits
 * (`PlaybackViewModel.persistEditedFileAsNewTake`), Orature to snapshot a chapter before handing
 * it to an external editor (`OratureChapterReviewViewModel`). Neither app is the natural owner,
 * and getting the naming or the directory wrong puts a take somewhere the project cannot find it.
 *
 * Not to be confused with the recorder's record-finalize path
 * (`RecorderViewModel.commitStagedTake`), which looks similar but is a different operation: it
 * builds its [Take] directly and stages and validates the WAV before committing.
 *
 * ### Threading
 * The file system work (naming, reserving the file, the copy) runs on [Dispatchers.IO]; the save
 * runs on the workbook's writer.
 */
class SaveAudioAsNewTake(private val projectFiles: ProjectFilesService) {

    /**
     * @param audioFile the audio to copy into the new take; must exist
     * @param chunk the chunk being recorded, or null for a whole-chapter take
     * @return the saved, selected take, or why it wasn't saved. A failed save removes the copied file.
     */
    suspend fun execute(
        workbook: Workbook,
        chapter: Chapter,
        chunk: Chunk?,
        recordable: Recordable,
        audioFile: File
    ): SaveResult<Take> {
        val audio = recordable.audio
        val newTake = withContext(Dispatchers.IO) {
            if (!audioFile.exists()) {
                throw IllegalStateException("Audio file to save does not exist: ${audioFile.path}")
            }
            val namer = WorkbookFileNamerBuilder.createFileNamer(
                workbook = workbook,
                chapter = chapter,
                chunk = chunk,
                recordable = recordable,
                rcSlug = workbook.sourceMetadataSlug
            )
            // Claims a file nothing else uses: if this take number's name is already on disk (an
            // older chunk layout's take, an orphan), it moves on to the next free number rather
            // than replacing that audio. The copy then writes into the file this call owns.
            val reserved = TakeFiles.reserve(
                // Creates the chapter directory if it is missing. formatChapterNumber() reads only
                // the chapter count, title and sort, so this agrees with every other take namer.
                projectFiles.of(workbook).getChapterAudioDir(workbook, chapter),
                audio.getNewTakeNumber()
            ) { namer.generateName(it, AudioFileFormat.WAV) }
            audioFile.copyTo(reserved.file, overwrite = true)
            NewTake(reserved.file, reserved.number)
        }

        // Saved and selected in one transaction, so nothing can select it before it has an id.
        val result = audio.insertTake(newTake, select = true)
        if (result !is SaveResult.Saved) withContext(Dispatchers.IO) { newTake.file.delete() }
        return result
    }
}
