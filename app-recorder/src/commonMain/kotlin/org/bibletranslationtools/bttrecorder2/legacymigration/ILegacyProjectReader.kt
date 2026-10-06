package org.bibletranslationtools.bttrecorder2.legacymigration

import java.io.File

/**
 * Reports what the old BTT-Recorder left on this device, as facts. It only reads: it never
 * changes or deletes anything, never throws, and never decides what happens to a project.
 */
interface ILegacyProjectReader {
    suspend fun read(): LegacyReadResult
}

sealed interface LegacyReadResult {
    /** The old app left nothing here: no database and no recordings. There is nothing to migrate, ever. */
    data object NothingInstalled : LegacyReadResult

    /** Something is here, but it can't be read now. Try again on a later launch; never delete. */
    data class Unreadable(val reason: String) : LegacyReadResult

    data class Read(
        val projects: List<LegacyProject>,
        /** Rows that couldn't be made into a project, chunk or take, and why. */
        val skippedRows: List<SkippedRow>,
        /** Book folders in the old app's storage that no project row claims. Never touched. */
        val unclaimedFolders: List<File>
    ) : LegacyReadResult
}

/** A database row the reader couldn't use: its table, its `_id`, and why. */
data class SkippedRow(val table: String, val id: Long?, val reason: String)
