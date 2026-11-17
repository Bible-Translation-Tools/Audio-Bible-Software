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
import io.reactivex.Observable
import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType

import kotlinx.coroutines.flow.Flow

interface IContentRepository : IRepository<Content> {
    // Insert for a collection
    fun insertForCollection(contentList: List<Content>, collection: Collection): Single<List<Content>>
    // Get all the chunks for a collection
    fun getByCollection(collection: Collection): Single<List<Content>>
    fun getByCollectionWithPersistentConnection(collection: Collection): Observable<List<Content>>
    // Get the collection meta-chunk
    fun getCollectionMetaContent(collection: Collection): Single<Content>
    // Get sources this content is derived from
    fun getSources(content: Content): Single<List<Content>>
    // Update the sources for a content
    fun updateSources(content: Content, sourceContents: List<Content>): Completable
    fun deleteForCollection(
        chapterCollection: Collection,
        typeFilter: ContentType? = null
    ): Completable
    fun linkDerivedToSource(
        derivedContents: List<Content>,
        sourceContents: List<Content>
    ): Completable

    fun updateAll(content: List<Content>): Completable

    suspend fun insertForCollectionSuspend(contentList: List<Content>, collection: Collection): List<Content>
    suspend fun getByCollectionSuspend(collection: Collection): List<Content>
    fun getByCollectionFlow(collection: Collection): Flow<List<Content>>
    suspend fun getCollectionMetaContentSuspend(collection: Collection): Content
    suspend fun getSourcesSuspend(content: Content): List<Content>
    suspend fun updateSourcesSuspend(content: Content, sourceContents: List<Content>)
    suspend fun deleteForCollectionSuspend(chapterCollection: Collection, typeFilter: ContentType? = null)
    suspend fun linkDerivedToSourceSuspend(derivedContents: List<Content>, sourceContents: List<Content>)
    suspend fun updateAllSuspend(content: List<Content>)
}
