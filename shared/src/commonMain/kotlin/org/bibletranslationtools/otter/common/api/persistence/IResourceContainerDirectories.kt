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

    /**
     * Where a newly imported source edition is kept:
     * `src/<creator>/<language>_<identifier>/<edition folder>`, where the edition folder is
     * [editionFolderName]. Editions imported before schema v16 stay in their `v<version>` folders;
     * a source's actual location is always its stored path.
     */
    fun getSourceEditionDirectory(container: ResourceContainer, editionCode: String): File {
        val dublinCore = container.manifest.dublinCore
        return internalSourceRCDirectory
            .resolve(dublinCore.creator)
            .resolve("${dublinCore.language.identifier}_${dublinCore.identifier}")
            .resolve(editionFolderName(dublinCore.version, editionCode))
    }

    /** Where an incoming source is unpacked before its edition, and so its folder, is known. */
    val sourceStagingDirectory: File get() = internalSourceRCDirectory.resolve(".staging")

    /**
     * Where a new derived RC (a project's target container) is created:
     * `der/<creator>/<source creator>/<source language>_<source identifier>/<source edition folder>/<target language>`,
     * where the edition folder is [editionFolderName] of the source edition, so two editions with the
     * same version label don't share one. Derived RCs created before this stay where their stored
     * path points.
     */
    fun getDerivedContainerDirectory(metadata: ResourceMetadata, source: ResourceMetadata, sourceEditionCode: String): File =
        resourceContainerDirectory
            .resolve("der")
            .resolve(metadata.creator)
            .resolve(source.creator)
            .resolve("${source.language.slug}_${source.identifier}")
            .resolve(editionFolderName(source.version, sourceEditionCode))
            .resolve(metadata.language.slug)
            .apply { mkdirs() }

    val resourceContainerDirectory: File
    val internalSourceRCDirectory: File
}

/**
 * An edition's folder name: its version label and short content code, for example `v12-3f9a2c`.
 * The code keeps two editions with the same label apart. Characters that aren't safe in a folder
 * name are replaced, so a label such as `"12.1"` (quotes included) gives `v12.1-3f9a2c`.
 */
fun editionFolderName(version: String, editionCode: String): String {
    val label = version.trim().trim('"', '\'').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "none" }
    return "v$label-$editionCode"
}
