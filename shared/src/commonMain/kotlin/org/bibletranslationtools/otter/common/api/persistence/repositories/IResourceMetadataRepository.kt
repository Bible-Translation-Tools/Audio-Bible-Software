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
import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.wycliffeassociates.resourcecontainer.ResourceContainer

interface IResourceMetadataRepository : IRepository<ResourceMetadata> {
    fun exists(metadata: ResourceMetadata): Single<Boolean>
    fun exists(predicate: (ResourceMetadata) -> Boolean): Single<Boolean>
    fun get(metadata: ResourceMetadata): Single<ResourceMetadata>
    fun insert(metadata: ResourceMetadata): Single<Int>
    fun update(metadata: ResourceMetadata, rc: ResourceContainer): Completable
    fun updateSource(metadata: ResourceMetadata, source: ResourceMetadata?): Completable
    fun getSource(metadata: ResourceMetadata): Maybe<ResourceMetadata>
    fun getAllSources(): Single<List<ResourceMetadata>>
    fun getAllDerivatives(metadata: ResourceMetadata): Single<List<ResourceMetadata>>
    // These functions are commutative
    fun addLink(firstMetadata: ResourceMetadata, secondMetadata: ResourceMetadata): Completable
    fun removeLink(firstMetadata: ResourceMetadata, secondMetadata: ResourceMetadata): Completable
    fun getLinked(metadata: ResourceMetadata): Single<List<ResourceMetadata>>

    suspend fun existsSuspend(metadata: ResourceMetadata): Boolean
    suspend fun existsSuspend(predicate: (ResourceMetadata) -> Boolean): Boolean
    suspend fun getSuspend(metadata: ResourceMetadata): ResourceMetadata
    suspend fun insertSuspend(metadata: ResourceMetadata): Int
    suspend fun updateSuspend(metadata: ResourceMetadata, rc: ResourceContainer)
    suspend fun updateSourceSuspend(metadata: ResourceMetadata, source: ResourceMetadata?)
    suspend fun getSourceSuspend(metadata: ResourceMetadata): ResourceMetadata?
    suspend fun getAllSourcesSuspend(): List<ResourceMetadata>
    suspend fun getAllDerivativesSuspend(metadata: ResourceMetadata): List<ResourceMetadata>
    suspend fun addLinkSuspend(firstMetadata: ResourceMetadata, secondMetadata: ResourceMetadata)
    suspend fun removeLinkSuspend(firstMetadata: ResourceMetadata, secondMetadata: ResourceMetadata)
    suspend fun getLinkedSuspend(metadata: ResourceMetadata): List<ResourceMetadata>
}
