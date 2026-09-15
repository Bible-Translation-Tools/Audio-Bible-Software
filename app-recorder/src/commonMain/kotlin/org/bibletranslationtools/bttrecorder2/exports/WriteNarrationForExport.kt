package org.bibletranslationtools.bttrecorder2.exports

import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.narration.WriteNarrationVerses
import org.bibletranslationtools.otter.common.domain.narration.narrationMarkerFor
import org.slf4j.LoggerFactory

/**
 * Gives a backup the narration audio Orature needs, for chapters that have no compiled take.
 *
 * Orature shows a chapter's audio from its narration — a scratch recording plus a map of which
 * regions belong to which unit — falling back to splitting a *selected chapter take* on its cues when
 * no narration is present. It never reads per-unit takes. So a project exported from here arrives
 * with its takes intact and its narration screen empty, unless one of those two things is in the
 * backup.
 *
 * A compiled chapter take covers the case where the whole chapter is recorded. It cannot cover a
 * chapter recorded in part, because compiling requires every unit to have audio — so those projects
 * could not reach Orature with audio at all. Writing the narration pair from whatever units do have
 * takes covers both, since only recorded units get an entry.
 *
 * **Only when there is no compiled chapter take.** Narration takes precedence over one when both are
 * present, so writing it unconditionally would override a chapter take the user compiled and marked
 * themselves, replacing their marker positions with ones derived from take boundaries.
 *
 * The files are written into the project because that is where the exporter collects them from, which
 * makes this a temporary change to the project rather than a step that only produces output — hence
 * [cleanUp], which every caller has to run. Narration left in a recorder project would be read as
 * authoritative by anything that opens it, ahead of the takes it was derived from.
 */
class WriteNarrationForExport(
    private val writeNarrationVerses: WriteNarrationVerses
) {

    private val logger = LoggerFactory.getLogger(WriteNarrationForExport::class.java)

    /**
     * Writes narration for every chapter of [workbook] that needs it.
     *
     * @return the chapters written, to be handed back to [cleanUp] once the export is done
     */
    fun execute(workbook: Workbook): List<Chapter> {
        val written = mutableListOf<Chapter>()
        workbook.target.chapters.toList().blockingGet().forEach { chapter ->
            if (chapter.audio.getSelectedTake() != null) {
                // A compiled chapter take is already there and outranks anything written here.
                return@forEach
            }
            val units = recordedUnits(workbook, chapter)
            if (units.isEmpty()) return@forEach

            if (writeNarrationVerses.execute(workbook, chapter, units)) {
                written += chapter
            } else {
                logger.warn("Could not write narration for ${chapter.title}; exporting without it")
            }
        }
        if (written.isNotEmpty()) {
            logger.info("Wrote narration for ${written.size} chapter(s) with no compiled take")
        }
        return written
    }

    /** Removes the narration written by [execute]. Safe to call for chapters that were skipped. */
    fun cleanUp(workbook: Workbook, chapters: List<Chapter>) {
        chapters.forEach { chapter ->
            if (!writeNarrationVerses.deleteNarration(workbook, chapter)) {
                logger.error("Narration written for export is still in ${chapter.title}")
            }
        }
    }

    /**
     * The chapter's recorded units, in unit order.
     *
     * Ordered by `sort` rather than by verse, because a chapter's book and chapter titles sort ahead
     * of its verses and the narration's regions follow one another in the order given. Bridged filler
     * rows are excluded by `chunks`, so a merged unit appears once.
     */
    private fun recordedUnits(workbook: Workbook, chapter: Chapter): List<WriteNarrationVerses.Unit> =
        chapter.chunks.blockingGet()
            .filter { it.contentType == ContentType.TEXT || it.contentType == ContentType.TITLE }
            .sortedBy { it.sort }
            .mapNotNull { chunk ->
                chunk.audio.getSelectedTake()
                    ?.takeIf { !it.isDeleted() && it.file.isFile }
                    ?.let { take ->
                        WriteNarrationVerses.Unit(
                            marker = narrationMarkerFor(chunk, workbook, chapter),
                            audio = take.file
                        )
                    }
            }
}
