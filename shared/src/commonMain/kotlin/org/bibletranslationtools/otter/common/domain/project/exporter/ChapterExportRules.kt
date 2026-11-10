package org.bibletranslationtools.otter.common.domain.project.exporter

import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.slf4j.LoggerFactory

/** What a chapter has, as far as exporting it goes. Each app works it out with its [ChapterCompletion]. */
data class ChapterStatus(
    /** Some of its audio is recorded. */
    val anyAudioRecorded: Boolean,
    /** All of its audio is recorded. */
    val audioRecorded: Boolean,
    /** The audio it would export has verse markers. */
    val verseMarkersPlaced: Boolean
)

/**
 * Works out a chapter's [ChapterStatus]. Each app has its own, because they finish a chapter
 * differently: Orature by project mode, the recorder chunk by chunk.
 */
interface ChapterCompletion {
    /** Reads the chapter's takes and files, so call it off the main thread. */
    fun status(workbook: Workbook, chapter: Chapter, mode: ProjectMode): ChapterStatus
}

/** Which chapters an export type takes. */
interface ExportRules {
    /** False when the exporter drops audio that has no verse markers. */
    val canExportWithoutMarkers: Boolean

    fun canExport(status: ChapterStatus): Boolean =
        status.audioRecorded && (canExportWithoutMarkers || status.verseMarkersPlaced)

    companion object {
        fun forType(type: ExportType): ExportRules = when (type) {
            ExportType.BACKUP -> BackupExportRules
            ExportType.SOURCE_AUDIO, ExportType.PUBLISH -> SourceAudioExportRules
            ExportType.LISTEN, ExportType.BURRITO_WRAPPER -> ListenExportRules
        }
    }
}

/** A backup restores the project as it is, so any chapter with some audio goes in. */
object BackupExportRules : ExportRules {
    override val canExportWithoutMarkers = true
    override fun canExport(status: ChapterStatus) = status.anyAudioRecorded
}

/** The source exporter skips a chapter take with no markers, which would export the chapter without audio. */
object SourceAudioExportRules : ExportRules {
    override val canExportWithoutMarkers = false
}

/** Exports the audio as it is, with its markers when it has any. */
object ListenExportRules : ExportRules {
    override val canExportWithoutMarkers = true
}

private val logger = LoggerFactory.getLogger("ChapterExportRules")

/**
 * Whether this chapter's own take has markers. A chapter without a take of its own has nothing to
 * check here: it's compiled from its chunks at export. An unreadable take counts as unmarked, since
 * offering it would export the chapter without audio.
 */
fun Chapter.selectedTakeHasMarkers(): Boolean {
    val take = getSelectedTake() ?: return true
    return runCatching { OratureAudioFile(take.file).getCues().isNotEmpty() }
        .onFailure { logger.warn("Couldn't read chapter $sort's markers", it) }
        .getOrDefault(false)
}
