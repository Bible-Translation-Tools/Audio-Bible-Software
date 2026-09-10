package org.bibletranslationtools.bttrecorder2.migration

import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.audio.WriteTakeMarkers
import org.bibletranslationtools.otter.common.domain.content.FileNamer
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.WriteDerivedManifest
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Writes one or more legacy projects out as a dialect backup, ready to be handed to the app's own
 * project import.
 *
 * Migration goes through import rather than writing the database itself, so everything import
 * already does — deriving the project from its source, scaffolding its files, registering takes,
 * applying selections and contributors — has a single implementation. This only has to produce a
 * container import recognises.
 *
 * The container is a **directory**, not a zip: `ImportProjectUseCase` identifies a format by loading
 * it, and both a resource container and an `IFileReader` can be backed by a directory, so nothing
 * has to be compressed and expanded again on the way in.
 *
 * ### What import requires
 *
 * - `manifest.yaml` naming the target language, the source, and the one book. Its
 *   `dublin_core.source` is what the source lookup matches against, and `projects[0].identifier` is
 *   the book.
 * - `.apps/orature/selected.txt`, whose presence is what marks a container as a resumable project
 *   and routes it to `OngoingProjectImporter`. Its lines are take paths relative to a project's
 *   audio directory, and they are what makes a take the selected one.
 * - `.apps/orature/project_mode.json`, without which import reads the container as an Orature 1
 *   project and takes a narration-migration path meant for a different layout.
 * - `.apps/orature/source/`, which has to exist even with nothing in it: import lists that
 *   directory, and listing an absent directory throws where an empty one yields nothing. It stays
 *   empty because the source is already in the database by the time this runs.
 * - Takes under `.apps/orature/takes/c<nn>/`, named so the chapter and verse can be read back out
 *   of the filename. That is the only thing tying a take to a unit — import matches a filename's
 *   verse number against the content rows of the project it derives.
 *
 * Merging happens here. Several legacy projects for one book and target language — `ulb`, `udb` and
 * `reg` — are staged together into one container, their takes numbered in one sequence, so import
 * sees a single project and no renumbering is needed afterwards.
 */
class StageLegacyBackup(
    private val store: LegacyRecorderStore,
    private val writeTakeMarkers: WriteTakeMarkers,
    private val writeDerivedManifest: WriteDerivedManifest
) {

    private val logger = LoggerFactory.getLogger(StageLegacyBackup::class.java)

    /**
     * [projects] must share a target language and book, and must all be verse mode: they are staged
     * as one project, so anything that would derive differently cannot be merged.
     *
     * [sourceUnits] is the source's own units per chapter — the ranges its content rows span. A take
     * is named after the unit containing its legacy verse rather than after the verse itself,
     * because import binds a take to a unit by the verse number in its filename and a bridged range
     * like `\v 24-25` is one unit starting at 24. Naming such a take `v25` binds it to the filler
     * row standing in for the bridged verse, where nothing shows it.
     */
    data class Request(
        val projects: List<LegacyProject>,
        val targetLanguage: Language,
        val sourceMetadata: ResourceMetadata,
        val bookTitle: String,
        val sourceUnits: Map<Int, List<IntRange>>
    )

    data class Result(
        val takesStaged: Int,
        val skipped: List<String>,
        /** Original filenames of the legacy source-audio containers carried in. */
        val sourceAudio: List<String>
    )

    /**
     * Stages [request] into [dir], which is emptied first so a resumed run cannot inherit a
     * half-written container from an earlier attempt.
     */
    fun execute(dir: File, request: Request): Result {
        require(request.projects.isNotEmpty()) { "nothing to stage" }

        dir.deleteRecursively()
        dir.mkdirs()

        val skipped = mutableListOf<String>()
        val selected = mutableListOf<String>()
        val takesStaged = stageTakes(dir, request, selected, skipped)
        val sourceAudio = stageSourceAudio(dir, request, skipped)

        writeManifest(dir, request)
        // Created explicitly: with every take skipped nothing else has made this directory, and
        // writing into a directory that is not there throws.
        File(dir, RcConstants.APP_SPECIFIC_DIR).mkdirs()
        File(dir, RcConstants.SELECTED_TAKES_FILE).writeText(
            selected.joinToString(separator = "\n", postfix = if (selected.isEmpty()) "" else "\n")
        )
        File(dir, RcConstants.PROJECT_MODE_FILE).writeText("""{"mode":"${ProjectMode.DIALECT}"}""")
        File(dir, RcConstants.SOURCE_DIR).mkdirs()

        logger.info(
            "Staged ${request.projects.joinToString { it.key }} at ${dir.path}: " +
                    "$takesStaged take(s), ${selected.size} selected, " +
                    "${sourceAudio.size} source audio, ${skipped.size} skipped"
        )
        return Result(takesStaged, skipped, sourceAudio)
    }

    // ---------------------------------------------------------------------------------------------
    // Takes
    // ---------------------------------------------------------------------------------------------

    /**
     * Copies every take of every project into the staged container, appending the paths of the
     * chosen ones to [selected].
     *
     * Projects are staged in a fixed order and each unit's takes numbered in one sequence across
     * them, so a legacy take 1 in `udb` follows `reg`'s takes rather than colliding with them.
     * Where two projects both chose a take for the same unit only the first can be selected, since
     * a unit has one selection.
     */
    private fun stageTakes(
        dir: File,
        request: Request,
        selected: MutableList<String>,
        skipped: MutableList<String>
    ): Int {
        val nextNumber = mutableMapOf<Pair<Int, Int>, Int>()
        val chosen = mutableSetOf<Pair<Int, Int>>()
        var staged = 0

        request.projects.sortedBy { it.versionSlug }.forEach { project ->
            project.chapters.forEach { chapter ->
                val chapterDir = File(dir, "${RcConstants.TAKE_DIR}/${chapterSlug(chapter.number)}")
                chapter.units.forEach { unit ->
                    val target = unitFor(request, chapter.number, unit)
                    if (target == null) {
                        skipped += "${project.versionSlug} c${chapter.number} v${unit.startVerse}: " +
                                "no source unit covers it"
                        return@forEach
                    }
                    val namer = namerFor(request, project, chapter, target)
                    unit.takes.sortedBy { it.number }.forEach { take ->
                        val source = store.takeFile(project, chapter.number, take)
                        if (source == null) {
                            skipped += "${project.versionSlug} c${chapter.number} v${unit.startVerse}: " +
                                    "missing file ${take.filename}"
                            return@forEach
                        }
                        val slot = chapter.number to target.first
                        val number = nextNumber.getOrDefault(slot, 1)
                        val destination = File(chapterDir, namer.generateName(number, AudioFileFormat.WAV))

                        val reason = stageTake(source, destination, target)
                        if (reason != null) {
                            skipped += "${project.versionSlug} c${chapter.number} v${unit.startVerse}: $reason"
                            return@forEach
                        }
                        nextNumber[slot] = number + 1
                        staged++

                        // Relative to the chapter directory's parent, which is what a project's
                        // audio directory becomes once import has copied the takes into it.
                        if (take.id == unit.chosenTakeId && chosen.add(slot)) {
                            selected += "${chapterSlug(chapter.number)}/${destination.name}"
                        }
                    }
                }
            }
        }
        return staged
    }

    /**
     * Copies one legacy take into the staged container and gives it its verse marker.
     *
     * The audio is copied byte for byte: legacy recordings are already 44.1 kHz mono 16-bit behind a
     * canonical header, so only the metadata needs rewriting. The marker has to be written here
     * because import registers a take without reading or adding cues, and a take with no cue is
     * dropped by source-audio export.
     *
     * @return null on success, or why the take could not be staged
     */
    private fun stageTake(source: File, destination: File, unit: IntRange): String? {
        val sourceFrames = framesOf(source) ?: return "${source.name}: not a readable WAV"
        if (sourceFrames <= 0) return "${source.name}: no audio"

        return try {
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)

            // One cue at frame 0 spanning the unit, as the recorder writes for a take at chunk
            // level. ALL_CUE_TYPES clears every Orature cue type first, and the rewrite re-emits
            // only the chunks `WavMetadata` understands, so the legacy LIST/INFO block and its
            // bare-numbered cues fall away with it.
            writeTakeMarkers.execute(
                destination,
                listOf(VerseMarker(unit.first, unit.last, 0)),
                WriteTakeMarkers.ALL_CUE_TYPES
            )

            val copiedFrames = framesOf(destination)
            if (copiedFrames != sourceFrames) {
                destination.delete()
                return "${source.name}: copied $copiedFrames frames, expected $sourceFrames"
            }
            null
        } catch (e: Exception) {
            logger.error("Could not stage ${source.path} -> ${destination.path}", e)
            runCatching { destination.delete() }
            "${source.name}: ${e.message ?: e::class.simpleName}"
        }
    }

    /**
     * Frame count from the WAV header, or null when the file will not parse. Legacy files can carry
     * a malformed audio-length field, on which `WavFile`'s constructor throws, so an unreadable take
     * is reported rather than fatal.
     */
    private fun framesOf(file: File): Int? = runCatching {
        if (!file.isFile) return null
        OratureAudioFile(file).totalFrames
    }.getOrNull()

    /**
     * The namer for one unit's takes.
     *
     * Only the chapter and verse numbers in the resulting name are read back by import; the counts
     * decide nothing but how widely those numbers are zero-padded. They are taken from the legacy
     * project itself, which is enough to pad the books that need three digits.
     */
    private fun namerFor(
        request: Request,
        project: LegacyProject,
        chapter: LegacyChapter,
        unit: IntRange
    ) = FileNamer(
        start = unit.first,
        end = unit.last,
        sort = unit.first,
        contentType = ContentType.TEXT,
        languageSlug = request.targetLanguage.slug,
        bookSlug = project.bookSlug,
        rcSlug = if (request.sourceMetadata.language.slug == request.targetLanguage.slug) {
            request.sourceMetadata.identifier
        } else {
            FileNamer.DEFAULT_RC_SLUG
        },
        chunkCount = chapter.units.maxOfOrNull { it.endVerse }?.toLong() ?: 1L,
        chapterCount = project.chapters.maxOfOrNull { it.number }?.toLong() ?: 1L,
        chapterTitle = "${chapter.number}",
        chapterSort = chapter.number
    )

    /**
     * The source unit a legacy unit's audio belongs to: the one covering its first verse.
     *
     * A chapter the source has no units for — one the legacy project recorded beyond the source's
     * versification — resolves to nothing, and its takes are reported rather than named after a unit
     * that does not exist.
     */
    private fun unitFor(request: Request, chapterNumber: Int, unit: LegacyUnit): IntRange? =
        request.sourceUnits[chapterNumber]?.firstOrNull { unit.startVerse in it }

    private fun chapterSlug(chapterNumber: Int) = "c%02d".format(chapterNumber)

    // ---------------------------------------------------------------------------------------------
    // Source audio
    // ---------------------------------------------------------------------------------------------

    /**
     * Copies each project's legacy Archive of Holding into the container's source-audio directory,
     * byte for byte and under its original name.
     *
     * Nothing decodes it: it is preserved so a decoder added later can read it, and import copies
     * that directory with the legacy container extensions explicitly allowed. Projects merged
     * together can name the same archive, so a name already staged is not copied twice.
     */
    private fun stageSourceAudio(dir: File, request: Request, skipped: MutableList<String>): List<String> {
        val outDir = File(dir, RcConstants.SOURCE_AUDIO_DIR)
        val staged = mutableListOf<String>()

        request.projects.forEach { project ->
            val blob = store.sourceAudioFile(project) ?: return@forEach
            if (blob.name in staged) return@forEach
            try {
                outDir.mkdirs()
                blob.copyTo(File(outDir, blob.name), overwrite = true)
                staged += blob.name
                // The extension decides whether the file survives a backup round trip, so an
                // unexpected one should be visible here rather than only when a backup drops it.
                logger.info(
                    "Staged legacy source audio '${blob.name}' (extension '${blob.extension}') " +
                            "for ${project.key}"
                )
            } catch (e: Exception) {
                logger.error("Could not stage legacy source audio ${blob.path}", e)
                skipped += "source audio ${blob.name}: ${e.message ?: e::class.simpleName}"
            }
        }
        return staged
    }

    // ---------------------------------------------------------------------------------------------
    // Manifest
    // ---------------------------------------------------------------------------------------------

    /**
     * Describes the project the staged takes belong to: derived from the mode source into the
     * legacy project's target language, for the one book.
     *
     * Contributors are the legacy projects' own, merged and de-duplicated; import copies them into
     * the project it creates.
     */
    private fun writeManifest(dir: File, request: Request) {
        val first = request.projects.first()
        writeDerivedManifest.execute(
            dir = dir,
            targetLanguage = request.targetLanguage,
            sourceMetadata = request.sourceMetadata,
            bookSlug = first.bookSlug,
            bookTitle = request.bookTitle,
            bookSort = first.bookNumber,
            contributors = contributorsOf(request)
        )
    }

    private fun contributorsOf(request: Request): List<String> =
        request.projects
            .flatMap { it.contributors?.split(',').orEmpty() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
