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
package org.bibletranslationtools.otter.common.domain.resourcecontainer

import io.reactivex.Single
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.api.persistence.IResourceContainerDirectories
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceContainerRepository
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File

class DeleteResourceContainer(
    private val directoryProvider: IResourceContainerDirectories,
    private val resourceContainerRepository: IResourceContainerRepository
) {
    private val logger = LoggerFactory.getLogger(DeleteResourceContainer::class.java)

    /**
     * Removes the source edition stored at [resourceContainer]'s path: its database rows, then its
     * files. Only that edition is removed, even when others of the same source share its version
     * label or folder. Folders it leaves empty are removed too, up to the source directory.
     */
    fun delete(resourceContainer: ResourceContainer): Single<DeleteResult> {
        val stored = resourceContainer.file
        return resourceContainerRepository
            .removeResourceContainer(resourceContainer)
            .doOnError {
                logger.error("Error when trying to delete rc: $stored.")
            }
            .doOnSuccess {
                if (it == DeleteResult.SUCCESS) {
                    logger.info("Deleting RC: $stored")
                    if (stored.deleteRecursively()) {
                        logger.info("RC deleted successfully!")
                        removeEmptyParents(stored.parentFile)
                    } else {
                        logger.error("RC partially deleted: $stored")
                    }
                }
            }
    }

    private fun removeEmptyParents(from: File?) {
        val sourceRoot = directoryProvider.internalSourceRCDirectory.canonicalFile
        var dir = from?.canonicalFile
        while (dir != null && dir != sourceRoot && dir.startsWith(sourceRoot) && dir.list()?.isEmpty() == true) {
            dir.delete()
            dir = dir.parentFile
        }
    }

    fun delete(rcFile: File): Single<DeleteResult> {
        ResourceContainer.load(rcFile).use {
            return delete(it)
        }
    }

    fun deleteSync(rcFile: File) = delete(rcFile).blockingGet()
}
