package org.bibletranslationtools.bttrecorder2.migration

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import org.bibletranslationtools.bttrecorder2.imports.AcceptAllImportCallback
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Migrates the legacy Android BTT-Recorder's projects into DIALECT projects, once, on first launch.
 *
 * Each project is staged as a dialect backup and handed to the app's own project import — see
 * [StageLegacyBackup] for the layout and why import does the work. Nothing here writes the database:
 * deriving the project, scaffolding its files, registering takes and applying selections all happen
 * inside import, the same way they do for a backup a user opens.
 *
 * Runs as an [Installable], so "already done" is answered by the installed-entity table and
 * per-project resumability by [MigrationLedger]. The ULB source each project derives from is
 * imported here as well, via [InitializeModeSources], and only once legacy data has been found, so
 * an install with no legacy data does no work.
 *
 * Legacy projects for one book, target language and recording mode are staged **together**, whatever
 * their legacy version slug, so `ulb`, `udb` and `reg` become one project with their takes merged and
 * numbered in one sequence. Each still keeps its own ledger entry and has its own legacy audio
 * deleted, since one can be staged successfully while another has an unreadable take.
 *
 * A project's units come from its source's content rows. Each mode has its own ULB text for that
 * reason: the chunk-mode one carries every legacy chunk as a bridged verse range, so
 * `deriveProjectFromVerses` — which import applies for a DIALECT project — yields the chunks rather
 * than single verses, and no verse-merging step is needed here.
 *
 * **Only the modes actually recorded are imported.** Both texts share an identifier and a language,
 * and the importer's own source lookup ignores the version, so with both present it resolves every
 * project to whichever was imported first regardless of mode. An install recording in one mode has
 * one source and cannot hit that; one recording in both can, until the importer matches on version.
 *
 * Migration is silent: no dialog, no summary. The log and the ledger are the only record of what
 * happened, so both have to carry enough detail to reconstruct a run.
 */
class MigrateLegacyRecorderProjects(
    private val store: LegacyRecorderStore,
    private val installedEntityRepo: IInstalledEntityRepository,
    private val collectionRepository: ICollectionRepository,
    private val contentRepository: IContentRepository,
    private val languageRepository: ILanguageRepository,
    private val initializeModeSources: InitializeModeSources,
    private val stageLegacyBackup: StageLegacyBackup,
    private val importProject: ImportProjectUseCase,
    private val directoryProvider: IDirectoryProvider
) : Installable {

    override val name = "LEGACY_RECORDER_PROJECTS"
    override val version = 1

    private val logger = LoggerFactory.getLogger(MigrateLegacyRecorderProjects::class.java)

    /**
     * The legacy projects that migrate as one project: one book, one target language, one recording
     * mode.
     *
     * The mode is part of the key because it decides the source, and so the units: a chunk-mode
     * project derives from the text whose units are the legacy chunks as bridged verse ranges, a
     * verse-mode one from the text whose units are single verses. Two projects for the same book in
     * different modes are two projects.
     */
    private data class Group(
        val targetLanguageSlug: String,
        val bookSlug: String,
        val mode: LegacyMode,
        val projects: List<LegacyProject>
    ) {
        val label: String get() = "$targetLanguageSlug/$bookSlug/$mode"
        val slug: String get() = "$targetLanguageSlug-$bookSlug-${mode.name.lowercase()}"
        val takeCount: Int get() = projects.sumOf { it.takeCount }
    }

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
            val pending = groupsOf(projects.filterNot { ledger.isDone(it.key) })
            if (pending.isEmpty()) {
                installedEntityRepo.install(this)
                logger.info("$name version: $version installed!")
                return@fromAction
            }

            // One source per mode in use, trimmed to the books that mode's projects reference; see
            // InitializeModeSources for why the subset matters. Only the modes present are imported,
            // which is also what keeps a single-mode install to a single ULB source.
            val required = pending.groupBy({ it.mode }, { it.bookSlug }).mapValues { it.value.toSet() }
            required.forEach { (mode, books) ->
                logger.info("Source required: ${modeSourceLabel(mode)} ($mode) for ${books.sorted()}")
            }
            if (!initializeModeSources.ensureImported(required)) {
                // Stay uninstalled and retry on the next launch rather than marking every
                // project permanently skipped.
                logger.error("Mode sources unavailable; deferring migration to the next launch.")
                return@fromAction
            }

            pending.forEachIndexed { index, group ->
                progressEmitter.onNext(
                    ProgressStatus(
                        titleKey = "initializingProjects",
                        subTitleKey = "loadingSomething",
                        subTitleMessage = group.label,
                        percent = index.toDouble() / pending.size
                    )
                )
                try {
                    migrate(group, ledger)
                } catch (e: Exception) {
                    // One bad group must never abort the rest.
                    logger.error("Failed to migrate legacy project group ${group.label}", e)
                    group.projects.forEach { project ->
                        ledger.put(
                            MigrationLedger.Entry(
                                key = project.key,
                                state = MigrationLedger.State.FAILED,
                                note = e.message ?: e::class.simpleName
                            )
                        )
                    }
                }
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

    /**
     * The legacy projects grouped by what they migrate into, in a fixed order so a resumed run
     * processes them the same way.
     */
    private fun groupsOf(projects: List<LegacyProject>): List<Group> = projects
        .groupBy { Triple(it.targetLanguageSlug, it.bookSlug, it.mode) }
        .map { (key, grouped) ->
            Group(key.first, key.second, key.third, grouped.sortedBy { it.versionSlug })
        }
        .sortedBy { it.label }

    // ---------------------------------------------------------------------------------------------
    // One group
    // ---------------------------------------------------------------------------------------------

    private fun migrate(group: Group, ledger: MigrationLedger) {
        // Logged before anything can fail, so the log always shows what was attempted.
        logger.info(
            "Migrating ${group.label} from ${group.projects.joinToString { it.versionSlug }}: " +
                    "book=${group.bookSlug}, takes=${group.takeCount}"
        )
        val targetLanguage = languageRepository.getBySlug(group.targetLanguageSlug)
            .onErrorReturnItem(UNKNOWN_LANGUAGE)
            .blockingGet()
            .takeIf { it != UNKNOWN_LANGUAGE }
            ?: return skipAll(group, ledger, "unknown target language '${group.targetLanguageSlug}'")

        val sourceCollection = resolveSourceCollection(group)
            ?: return skipAll(
                group,
                ledger,
                "no ${modeSourceLabel(group.mode)} source for ${group.bookSlug} — import it and relaunch"
            )
        val sourceMetadata = sourceCollection.resourceContainer
            ?: return skipAll(group, ledger, "the resolved source has no container metadata")

        // Legacy audio lives on external storage and staged containers under filesDir, so the copy
        // crosses mount points. Both the staged copy and the copy import makes into the project have
        // to fit, hence twice the group's audio size.
        val required = group.projects.sumOf { store.audioSizeBytes(it) } * 2
        val available = directoryProvider.getAppDataDirectory().usableSpace
        if (required > 0 && available in 1 until required + SPACE_HEADROOM_BYTES) {
            group.projects.forEach { project ->
                ledger.put(
                    MigrationLedger.Entry(
                        key = project.key,
                        state = MigrationLedger.State.FAILED,
                        note = "not enough free space: needs $required bytes, $available available"
                    )
                )
            }
            return
        }

        val stageDir = File(directoryProvider.tempDirectory, "legacy-${group.slug}")
        // Carried into the backup so import binds the project to this mode's text rather than to
        // whichever `ulb` it resolves first; see InitializeModeSources.trimmedContainer.
        val sourceContainer = initializeModeSources.trimmedContainer(group.mode, setOf(group.bookSlug))
        val staged = try {
            stageLegacyBackup.execute(
                stageDir,
                StageLegacyBackup.Request(
                    projects = group.projects,
                    targetLanguage = targetLanguage,
                    sourceMetadata = sourceMetadata,
                    bookTitle = sourceCollection.titleKey,
                    sourceUnits = sourceUnitsOf(sourceCollection),
                    sourceContainer = sourceContainer
                )
            )
        } finally {
            runCatching { sourceContainer?.delete() }
        }

        if (staged.takesStaged == 0) {
            stageDir.deleteRecursively()
            return skipAll(group, ledger, "no take could be staged: ${staged.skipped.joinToString("; ")}")
        }

        val result = try {
            // Every chapter, unconditionally: the callback exists because import asks which
            // chapters to take when a project already exists, and an unanswered request aborts it.
            importProject.import(stageDir, AcceptAllImportCallback(), null).blockingGet()
        } finally {
            stageDir.deleteRecursively()
        }

        if (result != ImportResult.SUCCESS) {
            logger.error("Import of ${group.label} returned $result")
            group.projects.forEach { project ->
                ledger.put(
                    MigrationLedger.Entry(
                        key = project.key,
                        state = MigrationLedger.State.FAILED,
                        takesSkipped = staged.skipped,
                        note = "import returned $result"
                    )
                )
            }
            return
        }
        logger.info(
            "Imported ${group.label} from '${sourceMetadataLabel(sourceCollection)}': " +
                    "${staged.takesStaged} take(s) staged, ${staged.skipped.size} skipped, " +
                    "units ${describeUnits(sourceCollection, group)}"
        )

        // A skipped take means the legacy audio stays put: it is the only remaining copy of what
        // could not be carried across. Recorded before any deletion, so a crash between the two
        // leaves only the deletion to retry.
        val notes = staged.skipped.takeIf { it.isNotEmpty() }?.joinToString("; ")
        group.projects.forEach { project ->
            val migrated = MigrationLedger.Entry(
                key = project.key,
                state = MigrationLedger.State.MIGRATED,
                takesCopied = project.takeCount,
                takesSkipped = staged.skipped,
                sourceAudio = staged.sourceAudio.joinToString().takeIf { it.isNotEmpty() },
                note = notes
            )
            ledger.put(migrated)

            if (staged.skipped.isNotEmpty()) {
                logger.warn(
                    "Legacy project ${project.key}: keeping legacy audio, " +
                            "${staged.skipped.size} take(s) skipped"
                )
                return@forEach
            }

            val deleted = store.deleteProjectAudio(project)
            if (!deleted) {
                logger.warn("Legacy audio for ${project.key} could not be deleted; leaving it in place")
            }
            ledger.put(
                migrated.copy(
                    state = MigrationLedger.State.CLEANED,
                    note = listOfNotNull(notes, "legacy audio not deleted".takeIf { !deleted })
                        .takeIf { it.isNotEmpty() }?.joinToString("; ")
                )
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sources
    // ---------------------------------------------------------------------------------------------

    /**
     * The source book collection the staged container names, which is what decides the project's
     * units.
     *
     * The legacy version slug (`ulb` / `udb` / `reg`) plays no part, so every legacy project for one
     * book and target language resolves to the same collection and therefore to the same derived
     * project, merging their takes.
     */
    private fun resolveSourceCollection(group: Group): Collection? {
        val version = InitializeModeSources.versionFor(group.mode)
        val sourceLanguage = group.projects
            .firstNotNullOfOrNull { it.sourceLanguageSlug?.takeIf(String::isNotBlank) }
            ?: LegacyProject.DEFAULT_SOURCE_LANGUAGE
        val candidates = collectionRepository.getSourceProjects().blockingGet()
        val resolved = candidates.firstOrNull { collection ->
            collection.slug == group.bookSlug &&
                    InitializeModeSources.matchesModeSource(collection.resourceContainer, version) &&
                    // The mode source is English, so a legacy project naming another source
                    // language has nothing to derive from and is reported rather than retargeted.
                    collection.resourceContainer?.language?.slug == sourceLanguage
        }
        if (resolved == null) {
            // Naming what is available distinguishes a missing import from an identity mismatch.
            val forBook = candidates
                .filter { it.slug == group.bookSlug }
                .mapNotNull { it.resourceContainer }
                .map { "${it.language.slug}/${it.identifier}/v${it.version}" }
                .sorted()
            logger.error(
                "No source for ${group.label}: wanted ${modeSourceLabel(group.mode)} for '${group.bookSlug}'. " +
                        "Available for that book: " +
                        (forBook.takeIf { it.isNotEmpty() }?.joinToString() ?: "none") +
                        ". Sources in the database: " +
                        candidates.mapNotNull { it.resourceContainer }
                            .map { "${it.language.slug}/${it.identifier}/v${it.version}" }
                            .distinct().sorted()
            )
        }
        return resolved
    }

    /**
     * The source's units per chapter, as the verse ranges its content rows span.
     *
     * This is what a staged take is named after. Bridged filler rows are left out, so a chunk-mode
     * source's `4-5` appears once as `4..5` and a take anywhere in it is named after verse 4 — the
     * unit the derived project has, and so the only name import can bind it to.
     */
    private fun sourceUnitsOf(sourceCollection: Collection): Map<Int, List<IntRange>> =
        collectionRepository.getChildren(sourceCollection).blockingGet()
            .associate { chapter ->
                chapter.sort to contentRepository.getByCollection(chapter).blockingGet()
                    .filter { it.type == ContentType.TEXT && !it.bridged }
                    .sortedBy { it.start }
                    .map { it.start..it.end }
            }

    /**
     * The first recorded chapter's unit ranges, for the log. A chunk-mode project reads as
     * `1-2, 3-4, 5-6`; reading `1, 2, 3` means it resolved to verse-by-verse text.
     */
    private fun describeUnits(sourceCollection: Collection, group: Group): String {
        val chapterNumber = group.projects
            .firstNotNullOfOrNull { project -> project.chapters.firstOrNull { it.units.isNotEmpty() } }
            ?.number
            ?: return "no recorded chapters"
        val units = sourceUnitsOf(sourceCollection)[chapterNumber] ?: return "c$chapterNumber missing"
        return "c$chapterNumber has ${units.size}: " +
                units.take(6).joinToString { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" } +
                if (units.size > 6) " …" else ""
    }

    private fun sourceMetadataLabel(source: Collection) =
        source.resourceContainer?.let { "${it.language.slug}/${it.identifier}/v${it.version}" } ?: "?"

    /** `en/ulb/v12-chunk`, for logs. */
    private fun modeSourceLabel(mode: LegacyMode) =
        "${InitializeModeSources.LANGUAGE}/${InitializeModeSources.IDENTIFIER}" +
                "/v${InitializeModeSources.versionFor(mode)}"

    // ---------------------------------------------------------------------------------------------

    private fun skipAll(group: Group, ledger: MigrationLedger, reason: String) {
        group.projects.forEach { ledger.put(skipped(it, reason)) }
    }

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

        /** Slack left over a group's audio size before attempting to stage and import it. */
        private const val SPACE_HEADROOM_BYTES = 32L * 1024 * 1024

        private val UNKNOWN_LANGUAGE = Language("", "", "", "", false, "")
    }
}
