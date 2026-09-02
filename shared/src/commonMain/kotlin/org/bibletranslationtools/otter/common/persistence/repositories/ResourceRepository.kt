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

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.rxkotlin.toObservable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.rx2.await
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.collections.MultiMap
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceRepository
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceLinkEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.*

class ResourceRepository(private val database: DaoProvider) : IResourceRepository {
    private val logger = LoggerFactory.getLogger(ResourceRepository::class.java)

    private val contentDao = database.contentDao
    private val contentTypeDao = database.contentTypeDao
    private val collectionDao = database.collectionDao
    private val takeDao = database.takeDao
    private val markerDao = database.markerDao
    private val resourceLinkDao = database.resourceLinkDao
    private val subtreeHasResourceDao = database.subtreeHasResourceDao
    private val resourceMetadataDao = database.resourceMetadataDao
    private val languageDao = database.languageDao
    private val contentMapper: ContentMapper = ContentMapper(contentTypeDao)
    private val takeMapper: TakeMapper = TakeMapper(database.checkingStatusDao)
    private val markerMapper: MarkerMapper = MarkerMapper()
    private val metadataMapper: ResourceMetadataMapper = ResourceMetadataMapper()
    private val languageMapper: LanguageMapper = LanguageMapper()

    override fun delete(obj: Content): Completable {
        return Completable
            .fromAction {
                contentDao.delete(contentMapper.mapToEntity(obj))
            }
            .doOnError { e ->
                logger.error("Error in delete with content: $obj", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getAll(): Single<List<Content>> {
        return Single
            .fromCallable {
                contentDao
                    .fetchAll()
                    .map(this::buildResource)
            }
            .doOnError { e ->
                logger.error("Error in getAll", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getResourceMetadata(content: Content): List<ResourceMetadata> {
        return resourceMetadataDao
            .resourceMetadataByContent(content.id)
            .map(this::mapToResourceMetadata)
    }

    override fun getResourceMetadata(collection: Collection): List<ResourceMetadata> {
        return resourceMetadataDao
            .resourceMetadataByCollection(collection.id)
            .map(this::mapToResourceMetadata)
    }

    override fun getSubtreeResourceMetadata(collection: Collection): List<ResourceMetadata> {
        return resourceMetadataDao
            .subtreeResourceMetadata(collection.id)
            .map(this::mapToResourceMetadata)
    }

    override fun getResources(content: Content, resourceMetadata: ResourceMetadata): Observable<Content> {
        return getResources { contentDao.resourcesForContent(content.id, resourceMetadata.id) }
    }

    /**
     * Returns collection-specific resources (does not return resources about the collection's children.)
     */
    override fun getResources(collection: Collection, resourceMetadata: ResourceMetadata): Observable<Content> {
        return getResources { contentDao.resourcesForCollection(collection.id, resourceMetadata.id) }
    }

    private fun getResources(fetch: () -> List<ContentEntity>): Observable<Content> {
        val contentStreamObservable = Observable.fromCallable {
            fetch().map(this::buildResource)
        }

        return contentStreamObservable
            .flatMap { it.iterator().toObservable() }
            .doOnError { e ->
                logger.error("Error in getResources", e)
            }
            .subscribeOn(Schedulers.io())
    }

    private fun insert(entity: ResourceLinkEntity): Completable {
        return Completable
            .fromAction {
                resourceLinkDao.insertNoReturn(entity)
            }
            .doOnError { e ->
                logger.error("Error in insert for link: $entity", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun linkToContent(resource: Content, content: Content, dublinCoreFk: Int) = insert(
        ResourceLinkEntity(
            id = 0,
            resourceContentFk = resource.id,
            contentFk = content.id,
            collectionFk = null,
            dublinCoreFk = dublinCoreFk
        )
    )

    override fun linkToCollection(resource: Content, collection: Collection, dublinCoreFk: Int) = insert(
        ResourceLinkEntity(
            id = 0,
            resourceContentFk = resource.id,
            contentFk = null,
            collectionFk = collection.id,
            dublinCoreFk = dublinCoreFk
        )
    )

    override fun update(obj: Content): Completable {
        return Completable
            .fromAction {
                val existing = contentDao.fetchById(obj.id)
                val entity = contentMapper.mapToEntity(obj)
                // Make sure we don't over write the collection relationship
                entity.collectionFk = existing.collectionFk
                contentDao.update(entity)
            }
            .doOnError { e ->
                logger.error("Error in update for content: $obj", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override suspend fun getAllSuspend(): List<Content> = getAll().await()
    override suspend fun updateSuspend(obj: Content) = update(obj).await()
    override suspend fun deleteSuspend(obj: Content) = delete(obj).await()

    override fun getResourcesFlow(collection: Collection, resourceMetadata: ResourceMetadata): Flow<Content> =
        getResources(collection, resourceMetadata).asFlow()

    override fun getResourcesFlow(content: Content, resourceMetadata: ResourceMetadata): Flow<Content> =
        getResources(content, resourceMetadata).asFlow()

    override suspend fun linkToContentSuspend(resource: Content, content: Content, dublinCoreFk: Int) =
        linkToContent(resource, content, dublinCoreFk).await()

    override suspend fun linkToCollectionSuspend(resource: Content, collection: Collection, dublinCoreFk: Int) =
        linkToCollection(resource, collection, dublinCoreFk).await()

    override fun calculateAndSetSubtreeHasResources(collectionId: Int) {
        database.transaction {
            val collectionEntity = collectionDao.fetchById(collectionId)
            val accumulator = MultiMap<Int, Int>()
            calculateAndSetSubtreeHasResources(collectionEntity, accumulator)
            subtreeHasResourceDao.insert(accumulator.kvSequence())
        }
    }

    private fun calculateAndSetSubtreeHasResources(
        collection: CollectionEntity,
        mMapCollectionToDublinId: MultiMap<Int, Int>
    ): Set<Int> {
        val childResources = collectionDao
            .fetchChildren(collection)
            .flatMap { calculateAndSetSubtreeHasResources(it, mMapCollectionToDublinId) }
        val myCollectionResources = resourceLinkDao
            .fetchByCollectionId(collection.id)
            .map { it.dublinCoreFk }
        val myContentResources = getContentResourceFksByCollection(collection.id)
        val union = childResources
            .union(myCollectionResources)
            .union(myContentResources)

        union.forEach {
            mMapCollectionToDublinId[collection.id] = it
        }

        return union
    }

    private fun getContentResourceFksByCollection(collectionId: Int): List<Int> {
        return resourceLinkDao.contentResourceMetadataFksByCollection(collectionId)
    }

    private fun buildResource(entity: ContentEntity): Content {
        // Same dead per-row fetchSources() as ContentRepository.buildContent had: two queries per
        // row whose result was never read.
        val selectedTake = entity
            .selectedTakeFk?.let { selectedTakeFk ->
                // Retrieve the markers
                val markers = markerDao
                    .fetchByTakeId(selectedTakeFk)
                    .map(markerMapper::mapFromEntity)
                takeMapper.mapFromEntity(takeDao.fetchById(selectedTakeFk), markers)
            }
        return contentMapper.mapFromEntity(entity, selectedTake)
    }

    private fun mapToResourceMetadata(entity: ResourceMetadataEntity): ResourceMetadata {
        val languageEntity = languageDao.fetchById(entity.languageFk)
            ?: throw NullPointerException("Could not find language with id ${entity.languageFk}.")

        val language = languageMapper
            .mapFromEntity(languageEntity)
        return metadataMapper.mapFromEntity(entity, language)
    }
}
