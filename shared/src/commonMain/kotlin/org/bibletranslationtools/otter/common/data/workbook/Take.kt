package org.bibletranslationtools.otter.common.data.workbook

import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.utils.computeFileChecksum
import java.io.File
import java.time.LocalDate

/**
 * A saved take: an immutable value. A change to a take (deleting, restoring, checking) is made
 * through the [AssociatedAudio] that owns it, which saves the change and then replaces this value
 * in its state.
 *
 * Equality is by value, all fields: an updated copy is a different value, so the state flows that hold
 * takes see the change. To ask whether two values are the same take, compare their [id]s.
 */
data class Take(
    val id: TakeId,
    val name: String,
    val file: File,
    val number: Int,
    val format: MimeType,
    val createdTimestamp: LocalDate,
    val deletedOn: LocalDate? = null,
    val checking: TakeCheckingState = TakeCheckingState(CheckingStatus.UNCHECKED, null)
) {
    fun isDeleted() = deletedOn != null

    /** Reads the file; call off the main thread. */
    fun checksum() = computeFileChecksum(file)

    fun getSavedChecksum() = checking.checksum

    override fun toString(): String = "Take($name, number=$number, deletedOn=$deletedOn, checking=${checking.status})"
}
