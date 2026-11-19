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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.utils.computeFileChecksum
import java.io.File

class ChapterTranslationBuilder(
    private val concatenateAudio: ConcatenateAudio,
    private val takeCreator: TakeCreator
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Compiles all chunks' takes into a single candidate take and compares it with
     * the currently selected take (chapter). If they are matching, the selected take
     * will be returned. Otherwise, the candidate take will be selected and returned.
     * The take comes in the [SaveResult] of saving it.
     */
    suspend fun getOrCompile(workbook: Workbook, chapter: Chapter): SaveResult<Take> {
        try {
            val takes = chapter.chunks()
                .filter { it.hasSelectedAudio() }
                .mapNotNull { it.audio.getSelectedTake()?.file }
            check(takes.isNotEmpty()) { "No takes to compile." }

            val compiled = concatenateAudio.execute(takes, includeMarkers = false).await()
            try {
                val checksum = withContext(Dispatchers.IO) { computeFileChecksum(compiled) }
                val result = if (checksum != null && !needNewChapterTake(checksum, chapter)) {
                    // The chapter's take already holds this audio. It is not added again.
                    val existing = chapter.audio.getSelectedTake()!!
                    val existingChecksum = withContext(Dispatchers.IO) { existing.checksum() }
                    chapter.audio.setChecking(existing, TakeCheckingState(CheckingStatus.VERSE, existingChecksum))
                } else {
                    val newTake = newChapterTake(workbook, chapter, compiled)
                    chapter.audio.insertTake(newTake)
                        .also { if (it !is SaveResult.Saved) withContext(Dispatchers.IO) { newTake.file.delete() } }
                }
                if (result is SaveResult.Saved) logger.info("Chapter ${chapter.sort} compiled successfully.")
                return result
            } finally {
                withContext(Dispatchers.IO) { compiled.delete() }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) logger.error("Error compiling chapter ${chapter.sort}", e)
            throw e
        }
    }

    private suspend fun newChapterTake(
        workbook: Workbook,
        chapter: Chapter,
        file: File
    ): Take {
        val namer = getFileNamer(workbook, chapter)
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

    private suspend fun getFileNamer(
        workbook: Workbook,
        chapter: Chapter,
    ): FileNamer {
        return WorkbookFileNamerBuilder.createFileNamer(
            workbook = workbook,
            chapter = chapter,
            chunk = null,
            recordable = chapter,
            rcSlug = workbook.sourceMetadataSlug
        )
    }

    /**
     * Returns whether the existing chapter take (compiled from chunks)
     * has changed since its last update by comparing it with the newly compiled take.
     *
     * @param checksum computed from the newly compiled take
     * @param chapter chapter to be compared with
     */
    private fun needNewChapterTake(checksum: String, chapter: Chapter): Boolean {
        return chapter.audio.getSelectedTake()?.checking?.let {
            checksum != it.checksum && it.status != CheckingStatus.VERSE
        } ?: true
    }
}
