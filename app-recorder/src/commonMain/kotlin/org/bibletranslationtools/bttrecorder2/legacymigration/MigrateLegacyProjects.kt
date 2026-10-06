package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.CancellationException
import org.bibletranslationtools.bttrecorder2.legacymigration.ImportMigrationBackup.Outcome
import org.bibletranslationtools.bttrecorder2.legacymigration.LegacyProjectPlan.Disposition
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationJournal.JournalWriteFailed
import org.bibletranslationtools.bttrecorder2.legacymigration.WriteMigrationBackup.ChapterTakeOutcome
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Migrates the old BTT-Recorder's projects: reads them, plans them onto the templates, writes,
 * imports and verifies each new project, and records where every legacy project stands in the
 * [journal] as it goes. Deleting the old files is not done here (M10); [JournalData.deletable]
 * says which may be deleted.
 *
 * A project the journal has finished with is never read into a run again. One a run was
 * interrupted on is done again, which adds nothing twice. Only what can change is tried again on a
 * later launch: low disk space, a failed import, an unexpected error. And at most [MAX_ATTEMPTS]
 * runs start on a project before it is kept, so a project that keeps failing, or crashes the app,
 * can't loop. Everything else that stops a project keeps it, for good.
 */
class MigrateLegacyProjects(
    private val reader: ILegacyProjectReader,
    private val installTemplates: InstallUlbTemplates,
    private val loadTemplateChunks: LoadTemplateChunks,
    private val planner: PlanLegacyMigration,
    private val writeBackup: WriteMigrationBackup,
    private val importBackup: ImportMigrationBackup,
    private val languages: ILanguageRepository,
    private val journal: MigrationJournal,
    private val tempFiles: ITempFileProvider,
    /** Free bytes where the migration writes. */
    private val freeBytes: () -> Long,
    /** The templates this version migrates onto; projects on the others are deferred. */
    private val enabledTemplates: Set<UlbTemplate> = setOf(UlbTemplate.VERSES),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val logger = LoggerFactory.getLogger(MigrateLegacyProjects::class.java)

    sealed interface Result {
        /** The old app left nothing on this device. */
        data object NothingInstalled : Result

        /** The old app's data couldn't be read; nothing was done. Tried again on the next launch. */
        data class Unreadable(val reason: String) : Result

        /** A template couldn't be installed; nothing was done. Tried again on the next launch. */
        data class TemplatesUnavailable(val reasons: List<String>) : Result

        /** The journal couldn't be written, so the run stopped there. Nothing may be deleted on its strength. */
        data class JournalFailed(val cause: JournalWriteFailed) : Result

        /** The run finished; [journal] is where every legacy project stands. */
        data class Ran(
            val journal: JournalData,
            val skippedRows: List<SkippedRow>,
            val unclaimedFolders: List<File>
        ) : Result
    }

    /** [onProgress] hears how many of the run's new projects are done, of how many. */
    suspend fun execute(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result {
        val read = when (val result = reader.read()) {
            LegacyReadResult.NothingInstalled -> return Result.NothingInstalled
            is LegacyReadResult.Unreadable -> return Result.Unreadable(result.reason)
            is LegacyReadResult.Read -> result
        }
        val run = Run(journal.load())
        val open = read.projects.filter { run.data.entryFor(it)?.state?.terminal != true }
        try {
            if (open.isNotEmpty()) {
                migrate(open, run, onProgress)?.let { return it }
            }
        } catch (e: JournalWriteFailed) {
            logger.error("Stopping the migration: its journal couldn't be written", e)
            return Result.JournalFailed(e)
        }
        return Result.Ran(run.data, read.skippedRows, read.unclaimedFolders)
    }

    /** The journal as this run changes it: every change is written before the run goes on. */
    private inner class Run(var data: JournalData) {
        fun record(project: LegacyProject, state: JournalState, reasons: List<JournalReason> = emptyList(), change: (JournalEntry) -> JournalEntry = { it }) {
            val previous = data.entryFor(project)
            val entry = change(
                JournalEntry(
                    key = project.journalKey,
                    legacyProjectId = project.id,
                    location = project.location,
                    state = state,
                    attempts = previous?.attempts ?: 0,
                    reasons = reasons,
                    // Told again only when something changed.
                    reported = previous?.reported == true && previous.state == state && previous.reasons == reasons,
                    updatedAt = now()
                )
            )
            data = data.with(entry)
            journal.write(data)
        }
    }

    /** Null when it ran; a result when it couldn't. */
    private suspend fun migrate(open: List<LegacyProject>, run: Run, onProgress: (Int, Int) -> Unit): Result? {
        val installed = installTemplates.execute(enabledTemplates)
        val editions = installed.values.filterIsInstance<InstallUlbTemplates.Outcome.Installed>()
            .associate { it.template to it.edition }
        if (editions.keys != enabledTemplates) {
            return Result.TemplatesUnavailable(
                installed.values.filterIsInstance<InstallUlbTemplates.Outcome.NotInstalled>().map { "${it.template}: ${it.reason}" }
            )
        }
        val chunks = loadTemplateChunks.execute(editions.mapValues { it.value.id }, open.map { it.book.slug }.toSet())
        val plan = planner.execute(open, chunks, enabledTemplates)

        plan.legacyProjects.forEach { legacy ->
            val project = legacy.legacyProject
            when (val disposition = legacy.disposition) {
                Disposition.Kept -> run.record(project, JournalState.KEPT, legacy.problems.map(JournalReasons::of))
                Disposition.NothingRecorded -> run.record(project, JournalState.NOTHING_RECORDED)
                is Disposition.Deferred -> run.record(
                    project, JournalState.DEFERRED,
                    listOf(JournalReason(JournalReasons.TEMPLATE_NOT_ENABLED, detail = disposition.template.name))
                )
                is Disposition.Migrated -> Unit
            }
        }
        plan.projects.forEachIndexed { index, project ->
            onProgress(index, plan.projects.size)
            val members = plan.legacyProjects.filter { (it.disposition as? Disposition.Migrated)?.into == project.key }
            migrate(project, members, editions.getValue(project.key.template), run)
        }
        onProgress(plan.projects.size, plan.projects.size)
        return null
    }

    private suspend fun migrate(project: MigrationProject, members: List<LegacyProjectPlan>, edition: ResourceMetadata, run: Run) {
        fun recordAll(state: JournalState, reason: JournalReason) = members.forEach {
            run.record(it.legacyProject, state, listOf(reason))
        }

        if (members.any { (run.data.entryFor(it.legacyProject)?.attempts ?: 0) >= MAX_ATTEMPTS }) {
            return recordAll(JournalState.KEPT, JournalReason(JournalReasons.FAILED_REPEATEDLY))
        }
        val needed = bytesNeeded(project)
        val free = freeBytes()
        if (free < needed) {
            return recordAll(JournalState.RETRY, JournalReason(JournalReasons.LOW_DISK_SPACE, detail = "$needed needed, $free free"))
        }
        val language = runCatching { languages.getBySlugSuspend(project.key.targetLanguage) }.getOrNull()
            ?: return recordAll(JournalState.KEPT, JournalReason(JournalReasons.LANGUAGE_UNKNOWN, detail = project.key.targetLanguage))

        members.forEach { member ->
            run.record(member.legacyProject, JournalState.PLANNED) { it.copy(attempts = it.attempts + 1) }
        }
        val backupFile = tempFiles.createTempFile("legacy-migration-${project.key.book}", ".zip")
        try {
            val backup = writeBackup.execute(project, edition, language, backupFile)
            if (backup.backup == null) {
                members.forEach { member ->
                    run.record(member.legacyProject, JournalState.KEPT, reasonsFor(member, backup) + JournalReason(JournalReasons.NOTHING_USABLE))
                }
                return
            }
            when (val outcome = importBackup.execute(project, edition, backup)) {
                is Outcome.Verified -> members.forEach { member -> recordVerified(member, backup, run) }
                is Outcome.Refused -> recordAll(JournalState.KEPT, JournalReasons.of(outcome.reason))
                is Outcome.ImportFailed ->
                    recordAll(JournalState.RETRY, JournalReason(JournalReasons.IMPORT_FAILED, detail = outcome.result.name))
                is Outcome.NotVerified -> members.forEach { member ->
                    run.record(member.legacyProject, JournalState.KEPT, outcome.failures.map(JournalReasons::of)) {
                        it.copy(imported = true)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: JournalWriteFailed) {
            throw e
        } catch (e: Exception) {
            logger.error("Migrating ${project.key} failed", e)
            recordAll(JournalState.RETRY, JournalReason(JournalReasons.FAILED, detail = e.message ?: e::class.simpleName))
        } finally {
            backupFile.delete()
        }
    }

    /**
     * A verified member is VERIFIED only when nothing that blocks deletion was left out of it;
     * otherwise its recordings are in the new app but it is kept, with what was left out.
     */
    private fun recordVerified(member: LegacyProjectPlan, backup: WriteMigrationBackup.Result, run: Run) {
        val id = member.legacyProject.id
        val ownTakes = backup.takes.filter { it.plan.legacyProjectId == id }
        val problems = member.problems + ownTakes.flatMap { it.problems }
        val chapters = ownTakes.filterIsInstance<ChapterTakeOutcome.Written>().map { it.chapter }.distinct().size
        val leftOut = ownTakes.any { it is ChapterTakeOutcome.LeftOut } || problems.any { it.blocksDeletion }
        run.record(
            member.legacyProject,
            if (leftOut) JournalState.KEPT else JournalState.VERIFIED,
            problems.map(JournalReasons::of)
        ) { it.copy(imported = true, chaptersMoved = chapters) }
    }

    private fun reasonsFor(member: LegacyProjectPlan, backup: WriteMigrationBackup.Result): List<JournalReason> =
        (member.problems + backup.takes.filter { it.plan.legacyProjectId == member.legacyProject.id }.flatMap { it.problems })
            .map(JournalReasons::of)

    /**
     * Room for the compiled chapter takes, the backup holding them, and the import's copy: about
     * three times the takes going in, and a margin.
     */
    private fun bytesNeeded(project: MigrationProject): Long =
        3 * project.chapters.sumOf { chapter -> chapter.takes.sumOf { plan -> plan.pieces.sumOf { it.take.file.length() } } } +
            SPACE_MARGIN

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val SPACE_MARGIN = 32L * 1024 * 1024
    }
}
