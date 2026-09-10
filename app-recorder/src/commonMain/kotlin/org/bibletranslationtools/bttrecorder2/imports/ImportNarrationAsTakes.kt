package org.bibletranslationtools.bttrecorder2.imports

import org.bibletranslationtools.bttrecorder2.takes.WriteTakeFromAudio
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.audio.BookMarker
import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.primitives.BOOK_TITLE_SORT
import org.bibletranslationtools.otter.common.data.primitives.CHAPTER_TITLE_SORT
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.WriteTakeMarkers
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.bibletranslationtools.otter.common.domain.narration.ExtractNarrationVerses
import org.slf4j.LoggerFactory

/**
 * Converts a just-imported project's narration audio into one take per unit.
 *
 * Orature stores a chapter as a single scratch recording plus a map of which regions belong to which
 * unit. This recorder's audio model is one take per unit, so an imported project arrives with its
 * audio on disk but nothing referencing it, and reads as empty. This turns that pairing into takes.
 *
 * Per-unit takes are the primitive here rather than one chapter take: a chapter take is *derived*
 * from them by compiling, which the chapter list already offers once every unit has audio. Importing
 * to takes therefore leaves a project in the same shape as one recorded here or migrated from the
 * legacy recorder, rather than a shape only playback understands.
 *
 * Once a chapter's takes are all written and verified, its narration files are deleted. Keeping them
 * would leave two copies of the same audio, and the narration copy is authoritative wherever it is
 * present — so an edit made here would be silently ignored in favour of stale audio.
 *
 * Deliberately not part of the shared importer: Orature reads narration natively and converting for
 * it would destroy the region map it edits through. Nothing here runs unless the recorder asks.
 */
class ImportNarrationAsTakes(
    private val extractNarrationVerses: ExtractNarrationVerses,
    private val workbookRepository: IWorkbookRepository,
    private val collectionRepository: ICollectionRepository,
    private val contentRepository: IContentRepository,
    takeRepository: ITakeRepository,
    writeTakeMarkers: WriteTakeMarkers
) {

    private val logger = LoggerFactory.getLogger(ImportNarrationAsTakes::class.java)

    private val takeWriter = WriteTakeFromAudio(takeRepository, writeTakeMarkers)

    /**
     * @param converted units that gained a take
     * @param chaptersCleaned chapters whose narration files were removed
     * @param problems units or chapters that were left alone, and why
     */
    data class Result(
        val converted: Int = 0,
        val chaptersCleaned: Int = 0,
        val problems: List<String> = emptyList()
    ) {
        val didWork: Boolean get() = converted > 0
    }

    /**
     * Converts every chapter of [derived] that carries narration audio.
     *
     * Returns without touching anything when the project has no narration, which is the common case
     * for a project exported by this recorder.
     */
    fun execute(derived: Collection): Result {
        val workbook = workbookRepository.getWorkbook(derived).blockingGet()
            ?: return Result(problems = listOf("${derived.slug}: could not open the workbook"))

        return try {
            convert(workbook, derived)
        } catch (e: Exception) {
            logger.error("Failed to convert narration audio for ${derived.slug}", e)
            Result(problems = listOf("${derived.slug}: ${e.message ?: e::class.simpleName}"))
        } finally {
            workbookRepository.closeWorkbook(workbook)
        }
    }

    private fun convert(workbook: Workbook, derived: Collection): Result {
        val chapters = workbook.target.chapters.toList().blockingGet()
        val chapterCollections = collectionRepository.getChildren(derived).blockingGet()

        var converted = 0
        var cleaned = 0
        val problems = mutableListOf<String>()

        chapters.forEach { chapter ->
            if (!extractNarrationVerses.hasNarration(workbook, chapter)) return@forEach

            val chapterCollection = chapterCollections.firstOrNull { it.sort == chapter.sort }
            if (chapterCollection == null) {
                problems += "c${chapter.sort}: no matching chapter in the project"
                return@forEach
            }

            val sections = extractNarrationVerses.execute(workbook, chapter)
            if (sections.isEmpty()) {
                // The narration is present but nothing placed, which means its units do not match
                // this project's. Reporting it matters: treating it as "nothing recorded" and
                // cleaning up would discard the only copy of the audio.
                problems += "c${chapter.sort}: narration units do not match this project's units, " +
                        "so its audio was left in place"
                return@forEach
            }

            val outcome = convertChapter(workbook, chapter, chapterCollection, sections)
            converted += outcome.converted
            problems += outcome.problems

            if (outcome.problems.isEmpty()) {
                if (extractNarrationVerses.deleteNarration(workbook, chapter)) {
                    cleaned++
                } else {
                    problems += "c${chapter.sort}: narration audio could not be deleted"
                }
            } else {
                logger.warn("c${chapter.sort}: keeping narration audio, ${outcome.problems.size} problem(s)")
            }
        }

        if (converted > 0) {
            writeSelectedTakes(derived)
            logger.info(
                "Converted $converted narration unit(s) into takes for ${derived.slug}; " +
                        "cleaned $cleaned chapter(s)"
            )
        }
        problems.forEach { logger.warn("${derived.slug}: $it") }
        return Result(converted, cleaned, problems)
    }

    private fun convertChapter(
        workbook: Workbook,
        chapter: Chapter,
        chapterCollection: Collection,
        sections: List<ExtractNarrationVerses.Section>
    ): Result {
        val contents = contentRepository.getByCollection(chapterCollection).blockingGet()
        val chunks = chapter.chunks.blockingGet()
        val destinationDir = workbook.projectFilesAccessor.getChapterAudioDir(workbook, chapter)

        var converted = 0
        val problems = mutableListOf<String>()

        sections.forEach { section ->
            val label = section.marker.label
            val chunk = chunkFor(section.marker, chunks)
            val content = chunk?.let { matchingContent(contents, it) }
            if (chunk == null || content == null) {
                problems += "c${chapter.sort} $label: no matching unit in the project"
                return@forEach
            }

            val namer = WorkbookFileNamerBuilder.createFileNamer(
                workbook = workbook,
                chapter = chapter,
                chunk = chunk,
                recordable = chunk,
                rcSlug = workbook.sourceMetadataSlug
            )

            when (
                val result = takeWriter.execute(
                    source = section.audio,
                    destinationDir = destinationDir,
                    namer = namer,
                    content = content,
                    preferredNumber = 1,
                    // Narration holds one recording per unit, and a unit with no take of its own
                    // has nothing else competing to be selected.
                    select = true,
                    // The marker narration recorded, so a title keeps its own kind.
                    marker = section.marker
                )
            ) {
                is WriteTakeFromAudio.Result.Copied -> {
                    converted++
                    contentRepository.update(content).blockingAwait()
                }

                is WriteTakeFromAudio.Result.AlreadyPresent -> {
                    // A repeated conversion of audio already imported. Re-apply the selection, since
                    // an interrupted run may have inserted the take without persisting it.
                    content.selectedTake = result.take
                    contentRepository.update(content).blockingAwait()
                }

                is WriteTakeFromAudio.Result.Skipped ->
                    problems += "c${chapter.sort} $label: ${result.reason}"
            }
            runCatching { section.audio.delete() }
        }

        return Result(converted = converted, problems = problems)
    }

    /**
     * The unit an extracted section belongs to.
     *
     * Matched on what the marker itself carries rather than on its label, so it does not depend on
     * how labels are formatted. A verse range matches only a unit spanning the same verses, which is
     * what keeps a chapter recorded verse by verse from being placed onto units that are ranges.
     */
    private fun chunkFor(marker: AudioMarker, chunks: List<Chunk>): Chunk? = when (marker) {
        is BookMarker -> chunks.firstOrNull { it.sort == BOOK_TITLE_SORT }
        is ChapterMarker -> chunks.firstOrNull { it.sort == CHAPTER_TITLE_SORT }
        is VerseMarker -> chunks.firstOrNull { it.start == marker.start && it.end == marker.end }
        else -> null
    }

    private fun matchingContent(contents: List<Content>, chunk: Chunk): Content? =
        contents.firstOrNull { it.type == chunk.contentType && it.sort == chunk.sort }

    /**
     * Writes the project's selected-take file from a reopened workbook. The takes were created after
     * the workbook above was constructed, so its own view of the selections is stale and writing
     * from it produces an empty file.
     */
    private fun writeSelectedTakes(derived: Collection) {
        val reopened = workbookRepository.getWorkbook(derived).blockingGet()
        if (reopened == null) {
            // Not fatal: the file is rebuilt from the database whenever the project is opened.
            logger.warn("Could not reopen ${derived.slug} to write its selected takes")
            return
        }
        try {
            reopened.projectFilesAccessor.writeSelectedTakesFile(reopened, isBook = true)
        } finally {
            workbookRepository.closeWorkbook(reopened)
        }
    }
}
