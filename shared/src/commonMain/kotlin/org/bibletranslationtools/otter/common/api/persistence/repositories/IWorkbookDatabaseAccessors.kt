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
package org.bibletranslationtools.otter.common.api.persistence.repositories

import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.workbook.DateHolder
import org.bibletranslationtools.otter.common.data.workbook.Translation

typealias ModelTake = org.bibletranslationtools.otter.common.data.primitives.Take

interface IWorkbookDatabaseAccessors {
    fun addContentForCollection(collection: Collection, chunks: List<Content>): Completable
    fun getChildren(collection: Collection): Single<List<Collection>>
    fun getCollectionMetaContent(collection: Collection): Single<Content>
    fun getContentByCollection(collection: Collection): Single<List<Content>>
    fun getContentByCollectionActiveConnection(collection: Collection): Observable<List<Content>>
    fun updateContent(content: Content): Completable
    fun getResources(content: Content, metadata: ResourceMetadata): Observable<Content>
    fun getResources(collection: Collection, metadata: ResourceMetadata): Observable<Content>
    fun getResourceMetadata(content: Content): List<ResourceMetadata>
    fun getResourceMetadata(collection: Collection): List<ResourceMetadata>
    fun getLinkedResourceMetadata(metadata: ResourceMetadata): List<ResourceMetadata>
    fun getSubtreeResourceMetadata(collection: Collection): List<ResourceMetadata>
    fun insertTakeForContent(take: ModelTake, content: Content): Single<Int>
    fun getTakeByContent(content: Content): Single<List<ModelTake>>
    fun updateTake(take: ModelTake): Completable
    fun deleteTake(take: ModelTake, date: DateHolder): Completable
    fun getSoftDeletedTakes(metadata: ResourceMetadata, projectSlug: String): Single<List<ModelTake>>
    fun getDerivedProject(sourceCollection: Collection): Maybe<Collection>
    fun getDerivedProjects(): Single<List<Collection>>
    fun getSourceProject(targetProject: Collection): Maybe<Collection>
    fun getTranslation(sourceLanguage: Language, targetLanguage: Language): Single<Translation>
    fun insertTranslation(translation: Translation): Single<Int>
    fun updateTranslation(translation: Translation): Completable
    fun clearContentForCollection(
        chapterCollection: Collection,
        typeFilter: ContentType
    ): Single<List<ModelTake>>

    fun getChunkCount(chapterCollection: Collection): Single<Int>
}
