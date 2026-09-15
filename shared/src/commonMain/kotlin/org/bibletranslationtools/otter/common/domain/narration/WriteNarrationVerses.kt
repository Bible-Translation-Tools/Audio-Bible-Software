package org.bibletranslationtools.otter.common.domain.narration

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.bibletranslationtools.otter.common.audio.AudioFile
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Writes a chapter's in-progress narration from one audio file per unit — the inverse of
 * [ExtractNarrationVerses].
 *
 * Narration keeps a chapter as a single scratch recording plus a map of which regions of it belong to
 * which unit, and that pairing is what it reads to show audio. An app whose audio model is one take
 * per unit therefore has to produce the pair for narration to see anything: per-unit takes alone are
 * never consulted.
 *
 * Only the units passed in get an entry, so a chapter recorded in part is expressible — which a
 * compiled chapter take is not, since compiling requires every unit to have audio.
 *
 * The units are written in the order given, each region following the last, so the scratch recording
 * reads start to finish as the chapter does. Callers order them by unit rather than by verse number,
 * because a chapter's book and chapter titles sort ahead of its verses.
 */
class WriteNarrationVerses(
    private val audioFileUtils: AudioFileUtils
) {

    private val logger = LoggerFactory.getLogger(WriteNarrationVerses::class.java)

    /**
     * One unit's audio to write.
     *
     * [marker] must be the marker narration derives for that unit — see [narrationMarkerFor] — since
     * a verse map is matched back onto the chapter's units by marker label, and an entry that matches
     * nothing is silently dropped.
     */
    data class Unit(val marker: AudioMarker, val audio: File)

    /**
     * Writes [units] as [chapter]'s narration, replacing any already there.
     *
     * @return true when a verse map was written. False means nothing was: either no units were given,
     *   or none of their audio could be read, and in both cases any narration already present is
     *   removed rather than left to disagree with the takes.
     */
    fun execute(workbook: Workbook, chapter: Chapter, units: List<Unit>): Boolean {
        val chapterDir = workbook.projectFilesAccessor.getChapterAudioDir(workbook, chapter)
        val scratch = File(chapterDir, CHAPTER_NARRATION_FILE_NAME)
        val verses = File(chapterDir, ACTIVE_VERSES_FILE_NAME)

        if (units.isEmpty()) {
            logger.info("No recorded units in ${chapter.title}; writing no narration")
            deleteNarration(workbook, chapter)
            return false
        }

        return try {
            chapterDir.mkdirs()
            // Start from nothing: appending to a scratch recording that is already there would leave
            // the earlier audio in place with no region pointing at it.
            scratch.delete()
            scratch.createNewFile()

            val nodes = appendUnits(scratch, units)
            if (nodes.isEmpty()) {
                logger.error("None of ${units.size} unit(s) in ${chapter.title} could be read")
                deleteNarration(workbook, chapter)
                return false
            }

            verses.writeText(activeVersesJson.encodeToString(ListSerializer(VerseNode.serializer()), nodes))
            logger.info("Wrote narration for ${chapter.title}: ${nodes.size} unit(s), ${scratch.length()} bytes")
            true
        } catch (e: Exception) {
            logger.error("Could not write narration for ${chapter.title}", e)
            deleteNarration(workbook, chapter)
            false
        }
    }

    /** Removes [chapter]'s narration. Safe to call when it is not there. */
    fun deleteNarration(workbook: Workbook, chapter: Chapter): Boolean {
        val chapterDir = workbook.projectFilesAccessor.getChapterAudioDir(workbook, chapter)
        return listOf(CHAPTER_NARRATION_FILE_NAME, ACTIVE_VERSES_FILE_NAME).all { name ->
            val file = File(chapterDir, name)
            runCatching { !file.exists() || file.delete() }
                .onFailure { logger.error("Could not delete ${file.path}", it) }
                .getOrDefault(false)
        }
    }

    /**
     * Appends each unit's audio to [scratch] and returns a node per unit that was readable.
     *
     * A region is a range of **byte** offsets into the scratch recording, while a marker's location
     * is a frame offset — the two units the format mixes. Regions are taken from the file's length
     * before and after the append rather than from the unit's own length, so a unit whose audio does
     * not read as expected cannot shift every later region.
     */
    private fun appendUnits(scratch: File, units: List<Unit>): List<VerseNode> {
        val scratchAudio = AudioFile(scratch)
        val frameSize = frameSizeOf(units)
        val nodes = mutableListOf<VerseNode>()

        units.forEach { unit ->
            val start = scratch.length()
            val appended = runCatching { audioFileUtils.appendFile(scratchAudio, unit.audio) }
                .onFailure { logger.error("Could not read ${unit.audio.name}", it) }
                .isSuccess
            val end = scratch.length()
            if (!appended || end <= start) {
                logger.warn("${unit.marker.label}: no audio appended, leaving it unrecorded")
                return@forEach
            }
            nodes += VerseNode(
                placed = true,
                marker = unit.marker.clone(location = (start / frameSize).toInt()),
                sectors = mutableListOf((start.toInt())..(end.toInt() - 1))
            )
        }
        return nodes
    }

    /**
     * Bytes per frame, taken from the units' own audio rather than assumed.
     *
     * Everything this app records is 44.1 kHz mono 16-bit, but reading it from the source keeps the
     * region arithmetic correct if that ever stops being true. A file that will not open falls back to
     * the recorded format instead of failing the whole chapter.
     */
    private fun frameSizeOf(units: List<Unit>): Long =
        units.firstNotNullOfOrNull { unit ->
            runCatching {
                val audio = AudioFile(unit.audio)
                (audio.channels * (audio.bitsPerSample / 8)).toLong().takeIf { it > 0 }
            }.getOrNull()
        } ?: DEFAULT_FRAME_SIZE_BYTES

    private companion object {
        const val DEFAULT_FRAME_SIZE_BYTES = 2L

        /** Matches how [ChapterRepresentation] reads the file it writes. */
        val activeVersesJson = Json { ignoreUnknownKeys = true }
    }
}
