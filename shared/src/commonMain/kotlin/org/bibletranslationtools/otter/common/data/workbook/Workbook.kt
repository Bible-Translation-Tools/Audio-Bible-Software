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


class Workbook(
    val source: Book,
    val target: Book,
    private val writer: WorkbookWriter
) {
    /**
     * Whether this workbook's changes are saved. Shows failures that no screen is waiting on, such
     * as settling a chapter's drafts when the user leaves it.
     */
    val saveStatus: kotlinx.coroutines.flow.StateFlow<SaveStatus> get() = writer.status

    /** True once [close] has run; a closed workbook refuses every change. */
    val isClosed: Boolean get() = writer.isClosed

    /** Refuses later changes and waits for the change in progress to be saved. */
    suspend fun close() = writer.close()



    val modifiedTs = target.modifiedTs
    val sourceMetadataSlug: String
        get() = source.resourceMetadata.identifier

    override fun hashCode(): Int {
        return listOf(
            source.collectionId,
            source.slug,
            source.language,
            target.collectionId,
            target.slug,
            target.language
        ).hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Workbook) return false

        if (source != other.source) return false
        if (target != other.target) return false

        return true
    }
}
