package org.bibletranslationtools.otter.common.domain.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.audio.AudioCue
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.DraftId
import org.bibletranslationtools.otter.common.data.workbook.RechunkReceipt
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.translation.ChunkAudioUseCase
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Rechunks a chapter, and switches between its layouts for undo and redo.
 *
 * The rows change through the chapter, in one transaction each. The files derived from the active
 * layout (the project's chunk file and the chunk markers in the chapter's source audio copy) are
 * rebuilt from the active layout after every change, never edited in place.
 */
class RechunkChapter(
    private val createChunks: CreateChunks,
    private val tempFiles: ITempFileProvider
) {
    private val logger = LoggerFactory.getLogger(RechunkChapter::class.java)

    /**
     * Makes the layout marked by [cues] in [sourceAudio] (the project's copy of the chapter's source
     * audio) the chapter's active one, keeping the old layout for undo.
     */
    suspend fun rechunk(workbook: Workbook, chapter: Chapter, cues: List<AudioCue>, sourceAudio: File): SaveResult<RechunkReceipt> {
        val rows = withContext(Dispatchers.IO) { createChunks.buildUserDefinedChunks(workbook, chapter.sort, cues) }
        val result = chapter.rechunk(rows)
        if (result is SaveResult.Saved) rebuildDerivedFiles(workbook, chapter, sourceAudio, cues)
        return result
    }

    /** Makes layout [to] active again (undo or redo of a rechunk) and rebuilds the derived files. */
    suspend fun switchDraft(workbook: Workbook, chapter: Chapter, to: DraftId, sourceAudio: File?): SaveResult<Unit> {
        val result = chapter.switchDraft(to)
        if (result is SaveResult.Saved) rebuildDerivedFiles(workbook, chapter, sourceAudio, cues = null)
        return result
    }

    /**
     * Rewrites the chunk file and the source audio's chunk markers from the active layout. [cues] are
     * the markers just placed; without them, each chunk's marker goes where its first verse starts.
     */
    private suspend fun rebuildDerivedFiles(workbook: Workbook, chapter: Chapter, sourceAudio: File?, cues: List<AudioCue>?) {
        val chunks = chapter.chunks().filter { it.contentType == ContentType.TEXT }
        withContext(Dispatchers.IO) {
            runCatching {
                createChunks.writeChunkFile(
                    workbook,
                    chapter.sort,
                    chunks.map {
                        Content(
                            sort = it.sort, labelKey = it.label, start = it.start, end = it.end,
                            text = it.textItem.text, format = "usfm", type = ContentType.TEXT, draftNumber = 2
                        )
                    }
                )
            }.onFailure { logger.error("Could not rewrite the chunk file for chapter ${chapter.sort}", it) }

            if (sourceAudio != null && sourceAudio.exists()) {
                runCatching {
                    val markers = cues ?: run {
                        val verses = OratureAudioFile(sourceAudio).getMarker<VerseMarker>()
                        chunks.mapNotNull { chunk -> verses.firstOrNull { it.start == chunk.start }?.let { AudioCue(it.location, chunk.title) } }
                    }
                    ChunkAudioUseCase(tempFiles, workbook.projectFilesAccessor).createChunkedSourceAudio(sourceAudio, markers)
                }.onFailure { logger.error("Could not rewrite the chunk markers for chapter ${chapter.sort}", it) }
            }
        }
    }
}
