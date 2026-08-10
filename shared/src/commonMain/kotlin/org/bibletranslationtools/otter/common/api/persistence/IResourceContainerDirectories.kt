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

import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File

/**
 * Where resource containers are unpacked and kept.
 *
 * Distinct from [IProjectDirectories]: a resource container is addressed by its own metadata
 * (or by the RC object itself), not by a project's (source, target, book) triple. Source RCs
 * are shared across every project that translates from them.
 */
interface IResourceContainerDirectories {

    /** Internal-use directory of the given source RC */
    fun getSourceContainerDirectory(container: ResourceContainer): File

    /** Internal-use directory of the given source RC */
    fun getSourceContainerDirectory(metadata: ResourceMetadata): File

    /** Internal-use directory of the given derived RC */
    fun getDerivedContainerDirectory(metadata: ResourceMetadata, source: ResourceMetadata): File

    val resourceContainerDirectory: File
    val internalSourceRCDirectory: File
}
