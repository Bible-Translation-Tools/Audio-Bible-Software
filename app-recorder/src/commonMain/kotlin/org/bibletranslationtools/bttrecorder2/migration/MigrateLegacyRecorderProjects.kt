package org.bibletranslationtools.bttrecorder2.migration

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Contributor
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.WriteTakeMarkers
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.bibletranslationtools.otter.common.domain.project.InitializeProjectFiles
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Migrates the legacy Android BTT-Recorder's projects into DIALECT projects, once, on first launch.
 *
 * Runs as an [Installable], so "already done" is answered by the installed-entity table and
 * per-project resumability by [MigrationLedger]. The ULB source each project derives from is
 * imported here as well, via [InitializeModeSources], and only once legacy data has been found, so
 * an install with no legacy data does no work.
 *
 * A project's units come from its source's content rows, and the chunk-mode source already carries
 * each legacy chunk as a bridged verse, so `deriveProjectFromVerses` produces the merged units and
 * there is no verse-merging step here.
 *
 * Legacy projects for one book and target language converge on a single migrated project per
 * recording mode, whatever their legacy version slug, and their takes merge into it (see
 * [copyTakes]). Each legacy project is still migrated and cleaned up on its own and keeps its own
 * ledger entry.
 *
 * Migration is silent: no dialog, no summary. The log and the ledger are the only record of what
 * happened, so both have to carry enough detail to reconstruct a run.
 */
class MigrateLegacyRecorderProjects(
    private val store: LegacyRecorderStore,
    private val installedEntityRepo: IInstalledEntityRepository,
    private val collectionRepository: ICollectionRepository,
    private val contentRepository: IContentRepository,
    private val takeRepository: ITakeRepository,
    private val workbookRepository: IWorkbookRepository,
    private val languageRepository: ILanguageRepository,
    private val createProject: CreateProject,
    private val initializeModeSources: InitializeModeSources,
    private val initializeProjectFiles: InitializeProjectFiles,
    private val writeTakeMarkers: WriteTakeMarkers,
    private val directoryProvider: IDirectoryProvider
) : Installable {

    override val name = "LEGACY_TR_MIGRATION"
    override val version = 1

    private val logger = LoggerFactory.getLogger(MigrateLegacyRecorderProjects::class.java)

    private val takeMigrator = MigrateLegacyTake(takeRepository, writeTakeMarkers)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable =
        Completable.fromAction {
            if (installedEntityRepo.getInstalledVersion(this) == version) {
                logger.info("$name up to date with version: $version")
                return@fromAction
            }
            // Existence check before anything expensive, so a fresh install neither opens the
            // legacy database nor imports a source.
            if (!store.hasLegacyData()) {
                logger.info("No legacy recorder data present; nothing to migrate.")
                installedEntityRepo.install(this)
                return@fromAction
            }

            val projects = store.readProjects()
            if (projects.isEmpty()) {
                logger.info("Legacy recorder data present but no readable projects; nothing to do.")
                installedEntityRepo.install(this)
                return@fromAction
            }

            logger.info("Migrating ${projects.size} legacy recorder project(s)...")
            progressEmitter.onNext(ProgressStatus(titleKey = "initializingProjects"))

            val ledger = MigrationLedger(ledgerFile())
            val pending = projects.filterNot { ledger.isDone(it.key) }

            // One source per recording mode in use, trimmed to the books those projects
            // reference; see InitializeModeSources for why the subset matters.
            val required = pending
                .groupBy({ it.mode }, { it.bookSlug })
                .mapValues { (_, books) -> books.toSet() }
            required.forEach { (mode, books) ->
                logger.info(
                    "Source required: ${InitializeModeSources.IDENTIFIER} " +
                            "v${InitializeModeSources.versionFor(mode)} ($mode) for ${books.sorted()}"
                )
            }
            if (pending.isNotEmpty() && !initializeModeSources.ensureImported(required)) {
                // Stay uninstalled and retry on the next launch rather than marking every
                // project permanently skipped.
                logger.error("Mode sources unavailable; deferring migration to the next launch.")
                return@fromAction
            }

            pending.forEachIndexed { index, project ->
                progressEmitter.onNext(
                    ProgressStatus(
                        titleKey = "initializingProjects",
                        subTitleKey = "loadingSomething",
                        subTitleMessage = project.key,
                        percent = index.toDouble() / pending.size
                    )
                )
                val entry = try {
                    migrate(project, ledger)
                } catch (e: Exception) {
                    // One bad project must never abort the rest.
                    logger.error("Failed to migrate legacy project ${project.key}", e)
                    MigrationLedger.Entry(
                        key = project.key,
                        state = MigrationLedger.State.FAILED,
                        note = e.message ?: e::class.simpleName
                    )
                }
                ledger.put(entry)
            }

            val outstanding = ledger.unfinished()
            if (outstanding.isEmpty()) {
                installedEntityRepo.install(this)
                logger.info("$name version: $version installed!")
            } else {
                // Retryable work remains, such as no free space or an unreadable take. Staying
                // uninstalled lets the next launch pick it up; the ledger keeps finished projects
                // finished.
                logger.warn(
                    "Migration incomplete, will retry next launch: " +
                            outstanding.joinToString { "${it.key}=${it.state}" }
                )
            }
        }.doOnError { logger.error("Error in $name", it) }

    // ---------------------------------------------------------------------------------------------
    // One project
    // ---------------------------------------------------------------------------------------------

    private fun migrate(project: LegacyProject, ledger: MigrationLedger): MigrationLedger.Entry {
        // Logged before anything can fail, so the log always shows what was attempted.
        logger.info(
            "Migrating ${project.key}: mode=${project.mode} -> source ${modeSourceLabel(project)}, " +
                    "book=${project.bookSlug}, chapters=${project.chapters.size}, takes=${project.takeCount}"
        )
        val targetLanguage = languageRepository.getBySlug(project.targetLanguageSlug)
            .onErrorReturnItem(UNKNOWN_LANGUAGE)
            .blockingGet()
            .takeIf { it != UNKNOWN_LANGUAGE }
            ?: return skipped(project, "unknown target language '${project.targetLanguageSlug}'")

        val sourceCollection = resolveSourceCollection(project)
            ?: return skipped(
                project,
                "no ${modeSourceLabel(project)} source in " +
                        "'${project.sourceLanguageSlug ?: LegacyProject.DEFAULT_SOURCE_LANGUAGE}' " +
                        "for ${project.bookSlug} — import it and relaunch"
            )

        // Legacy audio lives on external storage and migrated projects under filesDir, so the
        // copy crosses mount points and needs room for a second copy. Checked per project rather
        // than for the whole run, since each project's legacy audio is deleted once verified and
        // so frees space as the run progresses.
        val required = store.audioSizeBytes(project)
        val available = directoryProvider.getAppDataDirectory().usableSpace
        if (required > 0 && available in 1 until required + SPACE_HEADROOM_BYTES) {
            return MigrationLedger.Entry(
                key = project.key,
                state = MigrationLedger.State.FAILED,
                note = "not enough free space: needs $required bytes, $available available"
            )
        }

        val derived = createProject.create(
            sourceProject = sourceCollection,
            targetLanguage = targetLanguage,
            mode = ProjectMode.DIALECT,
            // Copies one content row per source unit under each chapter. For a chunk-mode source
            // those units are already the merged verse ranges.
            deriveProjectFromVerses = true
        ).blockingGet()

        val workbook = workbookRepository.getWorkbook(derived).blockingGet()
            ?: return skipped(project, "could not open the derived workbook")

        // The recorder relies on Orature scaffolding a project's files when it is opened, so
        // migration writes them itself: manifest.yaml, the source zip, selected.txt, chunks.json
        // and project_mode.json.
        val scaffolded = runBlocking { initializeProjectFiles.execute(workbook, ProjectMode.DIALECT) }
        if (scaffolded is InitializeProjectFiles.Result.Failed && !scaffolded.projectUsable) {
            throw IllegalStateException("project scaffolding failed at ${scaffolded.step}", scaffolded.cause)
        }

        // The units the project ended up with, which is where a chunk-mode project that resolved
        // to verse-by-verse text becomes visible.
        logger.info(
            "Derived ${project.key} from '${sourceMetadataIdentifier(sourceCollection)}': " +
                    describeUnits(derived, project)
        )

        val accessor = workbook.projectFilesAccessor
        project.contributors
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { names -> accessor.setContributorInfo(names.map(::Contributor)) }

        val skippedTakes = mutableListOf<String>()
        val copied = copyTakes(project, derived, workbook, accessor, skippedTakes)
        val sourceAudioName = copySourceAudio(project, accessor, skippedTakes)

        // Written from a reopened workbook: `fetchSelectedTakes` reads the workbook's relays,
        // which are populated at construction time and so predate every take copied above. Writing
        // from `workbook` would produce an empty file.
        workbookRepository.closeWorkbook(workbook)
        val reopened = workbookRepository.getWorkbook(derived).blockingGet()
        if (reopened != null) {
            reopened.projectFilesAccessor.writeSelectedTakesFile(reopened, isBook = true)
            workbookRepository.closeWorkbook(reopened)
        } else {
            // Not fatal: the file is rebuilt from the database whenever the project is opened.
            logger.warn("Could not reopen ${project.key} to write selected.txt")
        }

        val notes = skippedTakes.takeIf { it.isNotEmpty() }?.joinToString("; ")

        // Any skipped take means the legacy audio stays put.
        if (skippedTakes.isNotEmpty()) {
            logger.warn("Legacy project ${project.key}: keeping legacy audio, ${skippedTakes.size} take(s) skipped")
            return MigrationLedger.Entry(
                key = project.key,
                state = MigrationLedger.State.MIGRATED,
                takesCopied = copied,
                takesSkipped = skippedTakes,
                sourceAudio = sourceAudioName,
                note = notes
            )
        }

        // Recorded and flushed before deleting, so a crash between the two only leaves the
        // deletion to retry. Deleting first would leave a migrated project whose legacy audio is
        // gone, indistinguishable from an unmigrated project with no takes.
        val migrated = MigrationLedger.Entry(
            key = project.key,
            state = MigrationLedger.State.MIGRATED,
            takesCopied = copied,
            sourceAudio = sourceAudioName,
            note = notes
        )
        ledger.put(migrated)

        val deleted = store.deleteProjectAudio(project)
        if (!deleted) {
            logger.warn("Legacy audio for ${project.key} could not be deleted; leaving it in place")
        }
        return migrated.copy(
            state = MigrationLedger.State.CLEANED,
            note = listOfNotNull(notes, "legacy audio not deleted".takeIf { !deleted })
                .takeIf { it.isNotEmpty() }?.joinToString("; ")
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Takes
    // ---------------------------------------------------------------------------------------------

    /**
     * Copies one legacy project's takes into the migrated project.
     *
     * Several legacy projects can share a destination, since `ulb`, `udb` and `reg` for one book and
     * target language all migrate into one ULB project. Each numbers its own takes from 1, and
     * [MigrateLegacyTake] walks past occupied slots, so a later project's takes continue after an
     * earlier one's instead of overwriting them.
     */
    private fun copyTakes(
        project: LegacyProject,
        derived: Collection,
        workbook: Workbook,
        accessor: ProjectFilesAccessor,
        skipped: MutableList<String>
    ): Int {
        val chapterCollections = collectionRepository.getChildren(derived).blockingGet()
        val chapters = workbook.target.chapters.toList().blockingGet()
        var copied = 0

        project.chapters.forEach { legacyChapter ->
            val chapterCollection = chapterCollections.firstOrNull { it.sort == legacyChapter.number }
            val chapter = chapters.firstOrNull { it.sort == legacyChapter.number }
            if (chapterCollection == null || chapter == null) {
                skipped += "c${legacyChapter.number}: no matching chapter in the derived project"
                return@forEach
            }

            val contents = contentRepository.getByCollection(chapterCollection).blockingGet()
            val chunks = chapter.chunks.blockingGet()
            val destinationDir = accessor.getChapterAudioDir(workbook, chapter)

            legacyChapter.units.forEach { unit ->
                if (unit.takes.isEmpty()) return@forEach

                val content = contents.firstOrNull {
                    it.type == ContentType.TEXT && it.start == unit.startVerse
                }
                val chunk = chunks.firstOrNull {
                    it.contentType == ContentType.TEXT && it.start == unit.startVerse
                }
                if (content == null || chunk == null) {
                    skipped += "c${legacyChapter.number} v${unit.startVerse}: no matching unit " +
                            "(legacy range ${unit.startVerse}-${unit.endVerse})"
                    return@forEach
                }

                val namer = WorkbookFileNamerBuilder.createFileNamer(
                    workbook = workbook,
                    chapter = chapter,
                    chunk = chunk,
                    recordable = chunk,
                    rcSlug = workbook.sourceMetadataSlug
                )

                // A selection already made, either by a merged legacy project earlier in this
                // run or by an interrupted earlier run. Each merged project has its own chosen
                // take and only one can be selected, so keeping the first is arbitrary but stable
                // across a resumed run.
                val preselected = content.selectedTake
                var selectedChanged = false
                unit.takes.sortedBy { it.number }.forEachIndexed { index, legacyTake ->
                    val file = store.takeFile(project, legacyChapter.number, legacyTake)
                    if (file == null) {
                        skipped += "c${legacyChapter.number} v${unit.startVerse}: " +
                                "missing file ${legacyTake.filename}"
                        return@forEachIndexed
                    }
                    val isChosen = legacyTake.id == unit.chosenTakeId
                    val select = isChosen && preselected == null
                    if (isChosen && preselected != null) {
                        logger.info(
                            "c${legacyChapter.number} v${unit.startVerse}: keeping the existing " +
                                    "selection (${preselected.filename}) over ${project.key}'s"
                        )
                    }
                    when (
                        val result = takeMigrator.execute(
                            source = file,
                            destinationDir = destinationDir,
                            namer = namer,
                            content = content,
                            // Its position in legacy order, which merging bumps: a slot already
                            // holding another project's take moves this one to the next number.
                            preferredNumber = index + 1,
                            select = select
                        )
                    ) {
                        is MigrateLegacyTake.Result.Copied -> {
                            copied++
                            if (select) selectedChanged = true
                        }
                        // Already migrated, but an interrupted run may have died between
                        // inserting the take and persisting the selection, so re-apply it.
                        is MigrateLegacyTake.Result.AlreadyPresent -> if (select) {
                            content.selectedTake = result.take
                            selectedChanged = true
                        }
                        is MigrateLegacyTake.Result.Skipped ->
                            skipped += "c${legacyChapter.number} v${unit.startVerse}: ${result.reason}"
                    }
                }

                if (selectedChanged) {
                    contentRepository.update(content).blockingAwait()
                }
            }
        }
        return copied
    }

    // ---------------------------------------------------------------------------------------------
    // Source audio
    // ---------------------------------------------------------------------------------------------

    /**
     * Copies the legacy Archive of Holding into the project's source-audio directory byte for byte,
     * keeping its original filename and extension. Nothing here decodes it: it is preserved so a
     * decoder added later can read it, and so it travels inside `.orature` backups, which
     * `RcConstants.LEGACY_SOURCE_AUDIO_EXTENSIONS` is what allows.
     *
     * A copy rather than a move, because one archive can serve several projects and the originals
     * are never deleted, so a skipped copy loses nothing.
     *
     * @return the filename carried across, or null when there is none
     */
    private fun copySourceAudio(
        project: LegacyProject,
        accessor: ProjectFilesAccessor,
        skipped: MutableList<String>
    ): String? {
        val blob = store.sourceAudioFile(project) ?: return null
        val destination = File(accessor.sourceAudioDir, blob.name)
        if (destination.isFile && destination.length() == blob.length()) return blob.name

        val available = directoryProvider.getAppDataDirectory().usableSpace
        if (available in 1 until blob.length() + SPACE_HEADROOM_BYTES) {
            // Reported rather than fatal: the recorded takes matter more, and the original
            // archive stays where it is.
            skipped += "source audio ${blob.name}: not enough free space"
            return null
        }
        return try {
            accessor.sourceAudioDir.mkdirs()
            blob.copyTo(destination, overwrite = true)
            // The extension decides whether the file survives a backup round-trip, so an
            // unexpected one should be visible here rather than only when a backup drops it.
            logger.info(
                "Carried legacy source audio '${blob.name}' (extension '${blob.extension}') " +
                        "into ${project.key}"
            )
            blob.name
        } catch (e: Exception) {
            logger.error("Could not copy legacy source audio ${blob.path}", e)
            skipped += "source audio ${blob.name}: ${e.message ?: e::class.simpleName}"
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sources
    // ---------------------------------------------------------------------------------------------

    /**
     * The source book collection to derive from, which is what decides the project's units.
     *
     * Keyed on the legacy recording mode: `ulb` at `v12-chunk` for CHUNK, `v12-verse` for VERSE. The
     * legacy version slug (`ulb` / `udb` / `reg` / `v4`) plays no part, so several legacy projects
     * for one book and target language resolve to the same collection and therefore to the same
     * derived project, merging their takes (see [copyTakes]).
     *
     * Matching the version matters as much as matching the identifier: the plain `ulb` shares the
     * identifier and the language and covers all 66 books, so an identifier-only match would resolve
     * chunk-mode projects onto verse-by-verse text and lose the chunking.
     */
    private fun resolveSourceCollection(project: LegacyProject): Collection? {
        val version = InitializeModeSources.versionFor(project.mode)
        val sourceLanguage = project.sourceLanguageSlug?.takeIf { it.isNotBlank() }
            ?: LegacyProject.DEFAULT_SOURCE_LANGUAGE
        val candidates = collectionRepository.getSourceProjects().blockingGet()
        val resolved = candidates.firstOrNull { collection ->
            collection.slug == project.bookSlug &&
                    InitializeModeSources.matchesModeSource(collection.resourceContainer, version) &&
                    // The mode sources are English, so a legacy project naming another source
                    // language has nothing to derive from and is reported rather than retargeted.
                    collection.resourceContainer?.language?.slug == sourceLanguage
        }
        if (resolved == null) {
            // Naming what is available distinguishes a missing import from an identity mismatch.
            val forBook = candidates
                .filter { it.slug == project.bookSlug }
                .mapNotNull { it.resourceContainer }
                .map { "${it.language.slug}/${it.identifier}/v${it.version}" }
                .sorted()
            logger.error(
                "No source for ${project.key}: wanted ${modeSourceLabel(project)} for " +
                        "'${project.bookSlug}'. Available for that book: " +
                        (forBook.takeIf { it.isNotEmpty() }?.joinToString() ?: "none") +
                        ". Sources in the database: " +
                        candidates.mapNotNull { it.resourceContainer }
                            .map { "${it.language.slug}/${it.identifier}/v${it.version}" }
                            .distinct().sorted()
            )
        }
        return resolved
    }

    private fun sourceMetadataIdentifier(source: Collection) =
        source.resourceContainer?.let { "${it.language.slug}/${it.identifier}/v${it.version}" } ?: "?"

    /**
     * The first recorded chapter's unit ranges, for the log. A chunk-mode project reads as
     * `1-3, 4-5, 6-8`; reading `1, 2, 3` means it resolved to verse-by-verse text.
     */
    private fun describeUnits(derived: Collection, project: LegacyProject): String {
        val chapterNumber = project.chapters.firstOrNull { it.units.isNotEmpty() }?.number
            ?: return "no recorded chapters"
        val chapter = collectionRepository.getChildren(derived).blockingGet()
            .firstOrNull { it.sort == chapterNumber } ?: return "c$chapterNumber missing"
        val units = contentRepository.getByCollection(chapter).blockingGet()
            .filter { it.type == ContentType.TEXT }
            .sortedBy { it.start }
            .map { if (it.start == it.end) "${it.start}" else "${it.start}-${it.end}" }
        return "c$chapterNumber has ${units.size} unit(s): " + units.take(8).joinToString() +
                if (units.size > 8) " …" else ""
    }

    /** `en/ulb/v12-chunk`, for logs. */
    private fun modeSourceLabel(project: LegacyProject) =
        "${InitializeModeSources.LANGUAGE}/${InitializeModeSources.IDENTIFIER}" +
                "/v${InitializeModeSources.versionFor(project.mode)}"

    // ---------------------------------------------------------------------------------------------

    private fun skipped(project: LegacyProject, reason: String): MigrationLedger.Entry {
        logger.warn("Skipping legacy project ${project.key}: $reason")
        return MigrationLedger.Entry(
            key = project.key,
            state = MigrationLedger.State.SKIPPED,
            note = reason
        )
    }

    private fun ledgerFile() = File(directoryProvider.getAppDataDirectory("migration"), LEDGER_FILE)

    companion object {
        const val LEDGER_FILE = "legacy-recorder-migration.json"

        /** Slack left over a project's audio size before attempting its copy. */
        private const val SPACE_HEADROOM_BYTES = 32L * 1024 * 1024

        private val UNKNOWN_LANGUAGE = Language("", "", "", "", false, "")
    }
}
