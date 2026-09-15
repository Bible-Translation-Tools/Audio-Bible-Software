package org.bibletranslationtools.otter.common.domain.narration

import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.AudioBouncer
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Reads a chapter's in-progress narration and hands back one audio file per recorded unit.
 *
 * Narration keeps a chapter as a single growing scratch recording plus a map saying which regions of
 * it belong to which unit. An app whose audio model is one file per unit cannot read that pairing, so
 * this converts it: each unit's regions are read out in order and written as a standalone WAV.
 *
 * Regions are read through the narration representation rather than by slicing the scratch file
 * directly, which matters because a re-recorded unit owns several regions that need not be adjacent
 * or in order. Cutting the scratch file on unit boundaries would work only for a chapter recorded
 * once, straight through.
 *
 * Nothing here writes to the project. The caller decides what becomes of the files, and
 * [deleteNarration] is available for once it has committed them.
 */
class ExtractNarrationVerses(
    private val directoryProvider: ITempFileProvider,
    private val audioBouncer: AudioBouncer
) {

    private val logger = LoggerFactory.getLogger(ExtractNarrationVerses::class.java)

    /**
     * One unit's audio, extracted.
     *
     * [marker] identifies the unit and comes from the chapter's own content rows, so it can be
     * matched back to them. Its location is 0, not the offset the unit sat at within the chapter:
     * [audio] is a standalone file holding that unit alone, so anything written into it has to be
     * positioned relative to its own start. A chapter offset would land beyond the end of the file.
     *
     * [audio] itself carries no markers — stamping those belongs to whoever turns this into a take.
     */
    data class Section(val marker: AudioMarker, val audio: File)

    /** Whether [chapter] has narration audio worth extracting. */
    fun hasNarration(workbook: Workbook, chapter: Chapter): Boolean {
        val (scratch, verses) = narrationFiles(workbook, chapter)
        return scratch.isFile && scratch.length() > 0 && verses.isFile && verses.length() > 0
    }

    /**
     * Extracts every recorded unit of [chapter], in narration order.
     *
     * An empty result means the narration held nothing this project can place. That is expected when
     * the chapter was never recorded, but it also happens when the narration's units do not line up
     * with the project's own — the units are matched by label, so a project whose units are verse
     * ranges (`1-2`) places nothing from a narration recorded verse by verse. Callers that are about
     * to discard the narration must treat an empty result from a chapter that [hasNarration] as a
     * mismatch rather than as "nothing recorded".
     */
    fun execute(workbook: Workbook, chapter: Chapter): List<Section> {
        val representation = ChapterRepresentation(workbook, chapter)
        try {
            representation.loadFromSerializedVerses()
            val markers = representation.getActiveMarkers()
            if (markers.isEmpty()) {
                logger.info("No placed narration units in ${chapter.title}")
                return emptyList()
            }

            return markers.mapIndexedNotNull { index, marker ->
                extractSection(representation, index, marker)?.let {
                    Section(marker.clone(location = 0), it)
                }
            }
        } finally {
            representation.closeConnections()
        }
    }

    /** Deletes [chapter]'s narration files. Safe to call when they are already gone. */
    fun deleteNarration(workbook: Workbook, chapter: Chapter): Boolean {
        val (scratch, verses) = narrationFiles(workbook, chapter)
        return listOf(scratch, verses).all { file ->
            runCatching { !file.exists() || file.delete() }
                .onFailure { logger.error("Could not delete ${file.path}", it) }
                .getOrDefault(false)
        }
    }

    /**
     * Reads the unit at [index] into its own WAV.
     *
     * Locking the reader to the unit bounds it to that unit's regions, so a plain read from its start
     * yields exactly that audio however many regions it spans.
     */
    private fun extractSection(
        representation: ChapterRepresentation,
        index: Int,
        marker: AudioMarker
    ): File? {
        val destination = directoryProvider
            .createTempFile("narration-${marker.formattedLabel}", ".wav")
            .also(File::deleteOnExit)
        return try {
            val reader = representation.getAudioFileReader()
                    as ChapterRepresentation.ChapterRepresentationConnection
            reader.lockToVerse(index)
            audioBouncer.bounceAudio(destination, reader, emptyList())
            destination.takeIf { it.isFile && it.length() > 0 }
                ?: run {
                    logger.error("Extracted no audio for ${marker.formattedLabel}")
                    destination.delete()
                    null
                }
        } catch (e: Exception) {
            logger.error("Could not extract narration audio for ${marker.formattedLabel}", e)
            runCatching { destination.delete() }
            null
        }
    }

    /** The scratch recording and the region map, in that order. */
    private fun narrationFiles(workbook: Workbook, chapter: Chapter): Pair<File, File> {
        val chapterDir = workbook.projectFilesAccessor.getChapterAudioDir(workbook, chapter)
        return File(chapterDir, CHAPTER_NARRATION_FILE_NAME) to File(chapterDir, ACTIVE_VERSES_FILE_NAME)
    }
}
