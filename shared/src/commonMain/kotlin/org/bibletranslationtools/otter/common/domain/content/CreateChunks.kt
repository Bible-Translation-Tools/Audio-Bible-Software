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

import java.io.File
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.audio.AudioCue
import org.bibletranslationtools.otter.common.data.Chunkification
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import kotlinx.serialization.SerializationException
import org.bibletranslationtools.otter.common.OTTER_JSON
import org.bibletranslationtools.otter.common.data.CHUNKIFICATION


/**
 * Creates chunks for a given chapter of a book and adds the chunks the the workbook.
 * Chunks can either be user defined, or automatically generated based on the versification of the book and the
 * text content.
 *
 * @param chunkCreator A function that takes a list of Content and creates chunks for that list
 * @param chapterNumber The chapter number to create chunks for
 * @param workbook The workbook to add the created chunks to
 */
class CreateChunks {
    private val logger = LoggerFactory.getLogger(CreateChunks::class.java)


    /**
     * Creates chunks based on a list of audio cues, ultimately allowing for chunking based on placing markers
     * in a source audio.
     *
     * The range of verses will be determined by comparing the cues to the verse markers in the source audio corresponding
     * to the workbook.
     *
     * @param projectSlug The slug of the project to create chunks for
     * @param cues The list of audio cues to use to create chunks
     * @param draftNumber The draft number to create chunks form
     */
    fun createUserDefinedChunks(
        workbook: Workbook,
        chapter: Chapter,
        chunks: List<AudioCue>
    ): List<Content> {
        val projectSlug = workbook.target.slug
        val chapterNumber = chapter.sort

        logger.info("Creating ${chunks.size} user defined chunks for project: $projectSlug chapter: $chapterNumber")
        return buildChunkContent(workbook, chapterNumber, chunks)
    }

    private fun buildChunkContent(
        workbook: Workbook,
        chapter: Int,
        chunks: List<AudioCue>
    ): List<Content> {
        val projectSlug = workbook.target.slug
        val chapAudio = workbook.sourceAudioAccessor.getChapter(chapter, workbook.target)
        val sa = OratureAudioFile(chapAudio!!.file)
        val verseMarkers = sa.getMarker<VerseMarker>().map { it.toCue() }
        val chunkRanges = mapCuesToRanges(chunks)
        val verseRanges = mapCuesToRanges(verseMarkers)
        val chunkContents = mutableListOf<Content>()

        for ((idx, chunk) in chunkRanges.withIndex()) {
            val verses = findVerseRange(verseRanges, chunk)

            // use the chapter range and text if there are no verse markers (which would make the verse range empty)
            val chapterText = workbook.projectFilesAccessor.getChapterText(projectSlug, chapter)
            var start = 1
            var end = chapterText.size
            var text = ""

            // adjust verse range and text based on verse markers
            if (verses.isNotEmpty()) {
                start = verses.first()
                end = verses.last()
                val v = workbook.projectFilesAccessor.getChunkText(projectSlug, chapter, start, end)
                text = StringBuilder().apply { v.forEach { append("$it\n") } }.toString()
            } else {
                text = StringBuilder().apply { chapterText.forEach { append(it) } }.toString()
            }
            chunkContents.add(
                Content(
                    idx + 1,
                    "chunk",
                    start,
                    end,
                    null,
                    text,
                    "usfm",
                    ContentType.TEXT,
                    2
                )
            )
        }

        return chunkContents
    }

    fun writeChunkFile(
        workbook: Workbook,
        chapterNumber: Int,
        chunksToAdd: List<Content>
    ) {
        val chunks = mutableMapOf<Int, List<Content>>()

        val file: File = workbook.projectFilesAccessor.getChunkFile().apply {
            // Create empty file if it doesn't exist
            parentFile.mkdirs()
            createNewFile()
        }
        try {
            if (file.exists() && file.length() > 0) {
                val map: Chunkification = OTTER_JSON.decodeFromString(CHUNKIFICATION, file.readText())
                chunks.putAll(map)
            }
        } catch (e: SerializationException) {
            // clear file if it can't be read
            file.writer().use { }
        }

        chunks[chapterNumber] = chunksToAdd

        file.writeText(OTTER_JSON.encodeToString(CHUNKIFICATION, Chunkification(chunks)))
    }

    private data class VerseRange(val sort: Int, val startLoc: Int, val endLoc: Int)

    private fun findVerseRange(verseMarkers: List<VerseRange>, chunk: VerseRange): List<Int> {
        val verses = mutableListOf<Int>()
        if (verseMarkers.isEmpty()) {
            logger.error("Cannot find verse range, verse markers list is empty")
            return verses
        }

        for (verse in verseMarkers) {
            val chunkStartsAfterVerseStart = chunk.startLoc >= verse.startLoc
            val chunkStartsBeforeVerseEnd = chunk.startLoc <= verse.endLoc

            val chunkEndsAfterVerseStart = chunk.endLoc >= verse.startLoc
            val chunkEndsBeforeVerseEnd = chunk.endLoc <= verse.endLoc

            val verseStartsAfterChunkStart = verse.startLoc >= chunk.startLoc
            val verseEndsBeforeChunkEnd = verse.endLoc <= chunk.endLoc

            // chunk start inside verse
            if (chunkStartsAfterVerseStart && chunkStartsBeforeVerseEnd) {
                verses.add(verse.sort)
            }
            // chunk end inside verse
            else if (chunkEndsAfterVerseStart && chunkEndsBeforeVerseEnd) {
                verses.add(verse.sort)
            }
            // verse inside chunk
            else if (verseStartsAfterChunkStart && verseEndsBeforeChunkEnd) {
                verses.add(verse.sort)
            }
        }

        // If nothing is found, given that the last verse should reach end of file, or Int.MAX, it is likely that
        // the first verse has been moved forward, and that the chunk exists before any verse markers.
        if (verses.isEmpty()) {
            verses.add(verseMarkers.first().sort)
        }
        return verses
    }

    private fun mapCuesToRanges(cues: List<AudioCue>): List<VerseRange> {
        cues.sortedBy { it.location }
        val ranges = mutableListOf<VerseRange>()
        cues.forEachIndexed { idx, cue ->
            val end = if (cues.size > idx + 1) cues[idx + 1].location else Int.MAX_VALUE
            ranges.add(VerseRange(idx + 1, cue.location, end))
        }
        return ranges
    }
}
