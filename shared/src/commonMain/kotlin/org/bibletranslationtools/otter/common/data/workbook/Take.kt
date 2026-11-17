/**
 * Copyright (C) 2020-2024 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.data.workbook

import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.utils.computeFileChecksum
import java.io.File
import java.time.LocalDate

/**
 * A take: an immutable value. [id] is null until the take is saved ([AssociatedAudio.insertTake]
 * returns the saved copy, with its id). A change to a saved take (deleting, restoring, checking) is
 * made through the [AssociatedAudio] that owns it, which saves the change and then replaces this
 * value in its state; a take with no id is refused.
 *
 * Equality is by value, all fields: an updated copy is a different value, so the state flows that hold
 * takes see the change. To ask whether two values are the same take, compare their [id]s.
 */
data class Take(
    val name: String,
    val file: File,
    val number: Int,
    val format: MimeType,
    val createdTimestamp: LocalDate,
    val deletedOn: LocalDate? = null,
    val checking: TakeCheckingState = TakeCheckingState(CheckingStatus.UNCHECKED, null),
    val id: TakeId? = null
) {
    fun isDeleted() = deletedOn != null

    /** Reads the file; call off the main thread. */
    fun checksum() = computeFileChecksum(file)

    fun getSavedChecksum() = checking.checksum

    override fun toString(): String = "Take($name, number=$number, deletedOn=$deletedOn, checking=${checking.status})"
}
