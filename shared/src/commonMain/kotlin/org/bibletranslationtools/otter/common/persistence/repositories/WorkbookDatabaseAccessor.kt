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
package org.bibletranslationtools.otter.common.persistence.repositories

import io.reactivex.Maybe
import io.reactivex.Single
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDatabaseAccessors
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata

class WorkbookDatabaseAccessor(
    private val collectionRepo: ICollectionRepository,
    private val contentRepo: IContentRepository,
    private val resourceRepo: IResourceRepository,
    private val resourceMetadataRepo: IResourceMetadataRepository,
    private val takeRepo: ITakeRepository
) : IWorkbookDatabaseAccessors {

    override fun getChildren(collection: Collection) = collectionRepo.getChildren(collection)

    override fun getCollectionMetaContent(collection: Collection) = contentRepo.getCollectionMetaContent(collection)

    override fun getResources(content: Content, metadata: ResourceMetadata) =
        resourceRepo.getResources(content, metadata)

    override fun getResources(collection: Collection, metadata: ResourceMetadata) =
        resourceRepo.getResources(collection, metadata)

    override fun getResourceMetadata(content: Content) = resourceRepo.getResourceMetadata(content)
    override fun getResourceMetadata(collection: Collection) = resourceRepo.getResourceMetadata(collection)

    override fun getLinkedResourceMetadata(metadata: ResourceMetadata) =
        resourceMetadataRepo.getLinked(metadata).blockingGet()

    override fun getSubtreeResourceMetadata(collection: Collection) =
        resourceRepo.getSubtreeResourceMetadata(collection)

    override fun getSoftDeletedTakes(metadata: ResourceMetadata, projectSlug: String) =
        takeRepo.getSoftDeletedTakes(collectionRepo.getProjectBySlugAndMetadata(projectSlug, metadata).blockingGet())

    override fun getDerivedProject(sourceCollection: Collection): Maybe<Collection> {
        return collectionRepo.getDerivedProject(sourceCollection)
    }

    override fun getDerivedProjects(): Single<List<Collection>> = collectionRepo.getDerivedProjects()

    override fun getSourceProject(targetProject: Collection): Maybe<Collection> =
        collectionRepo.getSource(targetProject)
}