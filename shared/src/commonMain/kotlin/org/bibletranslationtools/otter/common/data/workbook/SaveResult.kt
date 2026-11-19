package org.bibletranslationtools.otter.common.data.workbook

import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException

/**
 * The outcome of a workbook change. Every change method returns one, and the in-memory state has
 * changed only when it is [Saved].
 *
 * Check it: an ignored result is how a failed save goes unnoticed.
 */
sealed interface SaveResult<out T> {
    /** Saved, and memory now shows the change. */
    data class Saved<out T>(val value: T) : SaveResult<T>

    /** Storage refused the change; memory is untouched. */
    data class Failed(val failure: SaveFailure, val cause: Throwable) : SaveResult<Nothing>

    /** The workbook refused the change before trying to save it; memory is untouched. */
    data class Rejected(val reason: RejectReason) : SaveResult<Nothing>
}

/** What a person can do about a failed save; each has a localized message in the apps. */
enum class SaveFailure {
    /** The disk or the database is full. */
    DISK_FULL,

    /** A file the change needed was missing. */
    FILE_MISSING,

    /** Anything else. */
    COULD_NOT_SAVE;

    companion object {
        fun of(error: Throwable): SaveFailure {
            val chain = generateSequence(error) { it.cause }.toList()
            return when {
                chain.any { it is FileNotFoundException || it is NoSuchFileException } -> FILE_MISSING
                chain.any { (it.message ?: "").let { m -> "No space left" in m || "disk is full" in m || "SQLITE_FULL" in m } } -> DISK_FULL
                else -> COULD_NOT_SAVE
            }
        }
    }
}

/** Why the workbook refused a change without trying to save it. */
enum class RejectReason {
    /** The workbook has been closed. */
    CLOSED,

    /** The take has not been saved, so it has no id to change. */
    UNSAVED
}
