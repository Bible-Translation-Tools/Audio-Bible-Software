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
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.utils.computeFileChecksum
import org.slf4j.LoggerFactory
import java.io.File

class ChapterTranslationBuilder(
    private val concatenateAudio: ConcatenateAudio,
    private val takeCreator: TakeCreator
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Compiles all chunks' selected takes into one candidate chapter take and compares it with the
     * chapter's selected take. If they match, that take is kept and returned; otherwise the candidate
     * is saved as a new chapter take, selected in the same transaction, and returned.
     *
     * @throws IllegalStateException when no chunk has a selected take
     */
    suspend fun getOrCompile(workbook: Workbook, chapter: Chapter): SaveResult<Take> {
        val takes = chapter.chunks()
            .filter { it.hasSelectedAudio() }
            .mapNotNull { it.audio.getSelectedTake()?.file }
        check(takes.isNotEmpty()) { "No takes to compile." }

        val compiled = concatenateAudio.execute(takes, includeMarkers = false).await()
        try {
            val checksum = withContext(Dispatchers.IO) { computeFileChecksum(compiled) }
            val existing = chapter.audio.getSelectedTake()
            val result = if (checksum != null && existing != null && !needNewChapterTake(checksum, existing)) {
                // The chapter's take already holds this audio. It is not added again.
                val existingChecksum = withContext(Dispatchers.IO) { existing.checksum() }
                chapter.audio.setChecking(existing, TakeCheckingState(CheckingStatus.VERSE, existingChecksum))
            } else {
                val newTake = newChapterTake(workbook, chapter, compiled)
                chapter.audio.insertTake(newTake, select = true)
                    .also { if (it !is SaveResult.Saved) withContext(Dispatchers.IO) { newTake.file.delete() } }
            }
            if (result is SaveResult.Saved) logger.info("Chapter ${chapter.sort} compiled successfully.")
            return result
        } finally {
            withContext(Dispatchers.IO) { compiled.delete() }
        }
    }

    private suspend fun newChapterTake(workbook: Workbook, chapter: Chapter, file: File): Take {
        val namer = WorkbookFileNamerBuilder.createFileNamer(
            workbook = workbook,
            chapter = chapter,
            chunk = null,
            recordable = chapter,
            rcSlug = workbook.sourceMetadataSlug
        )
        return withContext(Dispatchers.IO) {
            val chapterAudioDir = workbook.projectFilesAccessor.audioDir.resolve(namer.formatChapterNumber())
            // A file nothing else uses; the copy writes into the file this call owns.
            val reserved = TakeFiles.reserve(chapterAudioDir, chapter.audio.getNewTakeNumber()) {
                namer.generateName(it, AudioFileFormat.WAV)
            }
            file.copyTo(reserved.file, overwrite = true)
            takeCreator
                .createNewTake(reserved.number, reserved.file.name, chapterAudioDir, createEmpty = false)
                .copy(checking = TakeCheckingState(CheckingStatus.VERSE, computeFileChecksum(reserved.file)))
        }
    }

    /**
     * Whether the chapter's current take (compiled from chunks) has changed since it was compiled,
     * by comparing its saved checksum with the newly compiled audio's.
     */
    private fun needNewChapterTake(checksum: String, current: Take): Boolean =
        checksum != current.checking.checksum && current.checking.status != CheckingStatus.VERSE
}
