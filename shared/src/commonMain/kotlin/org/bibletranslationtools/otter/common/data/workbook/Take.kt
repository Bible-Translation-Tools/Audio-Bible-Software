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
 * Two takes are the same take when their [id]s are equal, whatever else differs, so an updated copy
 * still finds its place in a list.
 */
class Take(
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

    fun copy(
        deletedOn: LocalDate? = this.deletedOn,
        checking: TakeCheckingState = this.checking
    ) = Take(id, name, file, number, format, createdTimestamp, deletedOn, checking)

    override fun equals(other: Any?): Boolean = other is Take && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Take($name, number=$number, deletedOn=$deletedOn, checking=${checking.status})"
}
