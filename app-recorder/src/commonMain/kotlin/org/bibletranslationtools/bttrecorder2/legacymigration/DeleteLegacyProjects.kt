package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationJournal.JournalWriteFailed
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Deletes the old app's projects once their fate is settled and the user has made the archive
 * choice (M10): the only irreversible step of the migration.
 *
 * - Nothing is deleted until the journal holds the archive choice, saved or declined. If the app
 *   closes before the user chooses, nothing is deleted, and the offer comes back.
 * - Only projects the journal says may go are deleted: verified, nothing recorded, or kept for
 *   good (M10-5). One waiting for a retry or a later version stays.
 * - A project's take files are what the reader reports for it, read again now. Files no take
 *   points at are left. Folders that end up empty are removed.
 * - It is recorded as deleted only after all its files are gone. One that couldn't be deleted
 *   keeps its state and is tried again on the next launch.
 * - The old database goes once every project in it has been deleted.
 */
class DeleteLegacyProjects(
    private val reader: ILegacyProjectReader,
    private val storage: ILegacyStorage,
    private val journal: MigrationJournal,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val logger = LoggerFactory.getLogger(DeleteLegacyProjects::class.java)

    sealed interface Result {
        /** The user hasn't made the archive choice yet, so nothing was deleted. */
        data object AwaitingArchiveChoice : Result

        /** The old app's data couldn't be read now, so nothing was deleted. */
        data class Unreadable(val reason: String) : Result

        /** The journal couldn't be written; deleting stopped there. */
        data class JournalFailed(val cause: JournalWriteFailed) : Result

        /**
         * [deleted] projects went; [failed] couldn't all be deleted and wait for the next launch.
         * [databaseDeleted] when the old database went too.
         */
        data class Deleted(val deleted: Int, val failed: Int, val databaseDeleted: Boolean) : Result
    }

    suspend fun execute(): Result = withContext(Dispatchers.IO) {
        var data = journal.load()
        if (data.archive == null) return@withContext Result.AwaitingArchiveChoice
        val projects = when (val read = reader.read()) {
            LegacyReadResult.NothingInstalled -> emptyList()
            is LegacyReadResult.Unreadable -> return@withContext Result.Unreadable(read.reason)
            is LegacyReadResult.Read -> read.projects
        }
        val translations = storage.translationsDir

        var deleted = 0
        var failed = 0
        for (project in projects) {
            val entry = data.entryFor(project) ?: continue
            if (!entry.state.deletable) continue
            if (deleteFiles(project, translations)) {
                data = data.with(entry.copy(state = JournalState.LEGACY_DELETED, updatedAt = now()))
                try {
                    journal.write(data)
                } catch (e: JournalWriteFailed) {
                    logger.error("Stopping the delete: its journal couldn't be written", e)
                    return@withContext Result.JournalFailed(e)
                }
                deleted++
            } else {
                failed++
            }
        }

        val allGone = projects.all { data.entryFor(it)?.state == JournalState.LEGACY_DELETED }
        val databaseDeleted = allGone && storage.databaseFile != null && storage.deleteDatabase().also { gone ->
            if (!gone) logger.warn("The old app's database couldn't be deleted; tried again next launch")
        }
        Result.Deleted(deleted, failed, databaseDeleted)
    }

    /** Deletes [project]'s take files and the folders they leave empty. True when they are all gone. */
    private fun deleteFiles(project: LegacyProject, translations: File?): Boolean {
        val files = project.chapters.flatMap { chapter -> chapter.chunks.flatMap { chunk -> chunk.takes.map { it.file } } }
        val left = files.filter { file -> file.exists() && !file.delete() }
        if (left.isNotEmpty()) {
            logger.warn("Couldn't delete {} file(s) of {}, e.g. {}; tried again next launch", left.size, project.location, left.first())
            return false
        }
        files.mapNotNull { it.parentFile }.distinct().forEach { removeEmptyUpTo(it, translations) }
        return true
    }

    /** Removes [folder] and its parents while they are empty, stopping at [root] (never removed). */
    private fun removeEmptyUpTo(folder: File, root: File?) {
        val stop = root?.canonicalFile ?: return
        var current: File? = folder.canonicalFile
        while (current != null && current.path.startsWith(stop.path + File.separator)) {
            if (current.list()?.isEmpty() != true || !current.delete()) return
            current = current.parentFile
        }
    }
}
