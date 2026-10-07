package org.bibletranslationtools.bttrecorder2.legacymigration

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.rx2.rxCompletable
import org.bibletranslationtools.otter.common.api.persistence.config.AppStartupStep
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.slf4j.LoggerFactory

/**
 * Runs the legacy migration on the splash, before the user can create a project (M1). Only a
 * graph with [legacyMigrationModule] has it, which is the Android recorder's.
 *
 * It never fails startup: a migration that throws is logged and the app starts anyway. Nothing
 * has been deleted on its strength (that needs a verified state in the journal), and the next
 * launch tries again.
 */
class LegacyMigrationStartupStep(
    private val migrate: MigrateLegacyProjects,
    /** Run after it: deletes nothing before the archive choice, and retries what failed to delete since. */
    private val deleteProjects: DeleteLegacyProjects
) : AppStartupStep {

    private val logger = LoggerFactory.getLogger(LegacyMigrationStartupStep::class.java)

    override val name = "legacy-recorder-migration"
    override val version = 1

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable = rxCompletable {
        try {
            val result = migrate.execute { done, total ->
                // The splash words it: "book [done + 1] of [total]".
                progressEmitter.onNext(ProgressStatus(titleKey = PROGRESS_KEY, titleMessage = "$done/$total"))
            }
            logger.info("Legacy migration: {}", describe(result))
            if (result is MigrateLegacyProjects.Result.Ran) {
                logger.info("Legacy delete: {}", deleteProjects.execute())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("The legacy migration failed; the app starts without it and tries again next launch", e)
        }
    }

    private fun describe(result: MigrateLegacyProjects.Result): String = when (result) {
        is MigrateLegacyProjects.Result.Ran -> result.journal.projects.values
            .groupingBy { it.state }.eachCount()
            .let { "ran: $it; ${result.skippedRows.size} rows skipped, ${result.unclaimedFolders.size} folders unclaimed" }
        else -> result.toString()
    }

    companion object {
        /** The [ProgressStatus.titleKey] the splash recognises as this step's. */
        const val PROGRESS_KEY = "legacyMigration"

        /** [ProgressStatus.titleMessage] as `done/total`, or null when it isn't one. */
        fun progressOf(status: ProgressStatus): Pair<Int, Int>? =
            status.takeIf { it.titleKey == PROGRESS_KEY }?.titleMessage
                ?.split('/')?.takeIf { it.size == 2 }
                ?.let { (done, total) -> done.toIntOrNull()?.let { d -> total.toIntOrNull()?.let { d to it } } }
    }
}
