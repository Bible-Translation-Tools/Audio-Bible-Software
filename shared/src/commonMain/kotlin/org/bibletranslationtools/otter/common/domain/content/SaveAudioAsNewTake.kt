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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import java.io.File

/**
 * Registers an existing audio file as a new take of [recordable].
 *
 * Both apps had their own copy of this sequence — allocate the next take number, build the
 * project's file name for it, create the take in the chapter's audio directory, copy the audio
 * in, then insert. The recorder used it to save waveform edits
 * (`PlaybackViewModel.persistEditedFileAsNewTake`), Orature to snapshot a chapter before handing
 * it to an external editor (`OratureChapterReviewViewModel`). Neither app is the natural owner,
 * and getting the naming or the directory wrong puts a take somewhere the project cannot find it.
 *
 * The new take does end up selected — `WorkbookRepository` selects every take it inserts, once the
 * insert has returned an id. This does not select it itself; doing that raced the insert and wrote a
 * zero foreign key. See the note at the [execute] call to `insertTake`.
 *
 * Not to be confused with the recorder's record-finalize path
 * (`RecorderViewModel.commitStagedTake`), which looks similar but is a different operation: it
 * builds its [Take] directly and stages and validates the WAV before committing.
 *
 * ### Threading
 * The file system work — take number, naming (which reads chapter/chunk counts), take creation
 * and the copy — runs on [Dispatchers.IO]. The [insertTake][
 * org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio.insertTake] call runs on the
 * caller's context, because it pushes onto a relay the UI observes and each caller already chose
 * where it wants that to happen. Call from `Dispatchers.Main` to have the registration land on the
 * main thread.
 */
class SaveAudioAsNewTake(
    private val takeCreator: TakeCreator
) {

    /**
     * @param audioFile the audio to copy into the new take; must exist
     * @param chunk the chunk being recorded, or null for a whole-chapter take
     * @return the newly created take. It also becomes the selected take, but asynchronously and not
     *   by this method — see the note on [AssociatedAudio.insertTake] below.
     */
    suspend fun execute(
        workbook: Workbook,
        chapter: Chapter,
        chunk: Chunk?,
        recordable: Recordable,
        audioFile: File
    ): Take {
        val audio = recordable.audio
        val take = withContext(Dispatchers.IO) {
            if (!audioFile.exists()) {
                throw IllegalStateException("Audio file to save does not exist: ${audioFile.path}")
            }
            val takeNumber = audio.getNewTakeNumberSuspend()
            val namer = WorkbookFileNamerBuilder.createFileNamer(
                workbook = workbook,
                chapter = chapter,
                chunk = chunk,
                recordable = recordable,
                rcSlug = workbook.sourceMetadataSlug
            )
            val newTake = takeCreator.createNewTake(
                newTakeNumber = takeNumber,
                filename = namer.generateName(takeNumber, AudioFileFormat.WAV),
                // Creates the chapter directory if it is missing. Equivalent to resolving
                // audioDir against the namer's formatted chapter number by hand, which is what
                // the Orature copy did — formatChapterNumber() reads only the chapter count,
                // title and sort, so both namers agree on it.
                audioDir = workbook.projectFilesAccessor.getChapterAudioDir(workbook, chapter),
                createEmpty = false
            )
            audioFile.copyTo(newTake.file, overwrite = true)
            newTake
        }

        // Insert ONLY. Do not add a selectTake() here.
        //
        // WorkbookRepository already selects every newly inserted take, and it does so at the one
        // moment when that is safe: inside insertTakeForContent's subscribe, after
        // `modelTake.id = insertionId`. Selecting from here instead ran synchronously on this thread
        // while the insert was still in flight on Schedulers.io, so `takeMap[wbTake]` handed back a
        // take whose id was still 0 and the content update wrote `selected_take_fk = 0` — a foreign
        // key violation that surfaced as "saving a recording" failing outright.
        //
        // The JavaFX app never made this call: ChapterReviewViewModel (which this use case was
        // extracted from), PeerEditViewModel and NarrationHistory all insert and leave selection to
        // the repository. The recorder's own commitStagedTake does the same and has always worked.
        audio.insertTake(take)
        return take
    }
}
