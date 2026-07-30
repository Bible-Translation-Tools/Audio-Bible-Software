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
package org.bibletranslationtools.otter.common.api.persistence

import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import java.io.File

/**
 * Where one project's files live, addressed by (source, target, book).
 *
 * Each location comes in two overloads — one taking the book [Collection], one taking its slug —
 * because callers that already hold the collection should not have to reach into it, and callers
 * that only know the slug (importers reading a manifest) should not have to load it.
 */
interface IProjectDirectories {

    /** Directory for project */
    fun getProjectDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        book: Collection
    ): File

    /** Directory for project */
    fun getProjectDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File

    /** Directory for project audio */
    fun getProjectAudioDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        book: Collection
    ): File

    /** Directory for project audio */
    fun getProjectAudioDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File

    /** Directory for source */
    fun getProjectSourceDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        book: Collection
    ): File

    /** Directory for source */
    fun getProjectSourceDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File

    fun getProjectSourceAudioDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File
}
