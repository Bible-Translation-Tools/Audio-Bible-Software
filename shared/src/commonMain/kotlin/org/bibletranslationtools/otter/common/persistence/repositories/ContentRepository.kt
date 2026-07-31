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

import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.rx2.await
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.ContentMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.MarkerMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.TakeMapper
import java.lang.IllegalStateException
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.CollectionMapper

class ContentRepository(
    database: IAppDatabase
) : IContentRepository {
    private val logger = LoggerFactory.getLogger(ContentRepository::class.java)

    private val activeConnections = mutableMapOf<Collection, BehaviorRelay<List<Content>>>()

    private val contentDao = database.contentDao
    private val takeDao = database.takeDao
    private val markerDao = database.markerDao
    private val contentTypeDao = database.contentTypeDao
    private val contentMapper: ContentMapper = ContentMapper(contentTypeDao)
    private val collectionMapper = CollectionMapper()
    private val takeMapper: TakeMapper = TakeMapper(database.checkingStatusDao)
    private val markerMapper: MarkerMapper = MarkerMapper()

    override fun getByCollection(collection: Collection): Single<List<Content>> {
        return Single
            .fromCallable {
                contentDao
                    .fetchByCollectionId(collection.id)
                    .map(this::buildContent)
                    .filter { !it.bridged }
            }
            .doOnError { e ->
                logger.error("Error in getByCollection for collection: $collection", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getByCollectionWithPersistentConnection(collection: Collection): Observable<List<Content>> {
        activeConnections.getOrDefault(collection, null)?.let { return it }

        val connection = BehaviorRelay.create<List<Content>>()
        activeConnections[collection] = connection
        getByCollection(collection)
            .map {
                connection.accept(it)
            }
            .subscribeOn(Schedulers.io())
            .subscribe()

        return connection
    }

    override fun getCollectionMetaContent(collection: Collection): Single<Content> {
        return Single
            .fromCallable {
                contentDao
                    .fetchByCollectionIdAndType(collection.id, ContentType.META)
                    .map(this::buildContent)
                    .minByOrNull { it.start }
                    ?: throw IllegalStateException("Missing meta info for chapter.")
            }
            .doOnError { e ->
                logger.error("Error in getByCollectionMetaContent for collection: $collection", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getSources(content: Content): Single<List<Content>> {
        return Single
            .fromCallable {
                contentDao
                    .fetchSources(contentMapper.mapToEntity(content))
                    .map(this::buildContent)
            }
            .doOnError { e ->
                logger.error("Error in getSources for content: $content", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun updateSources(content: Content, sourceContents: List<Content>): Completable {
        return Completable
            .fromAction {
                contentDao.updateSources(
                    contentMapper.mapToEntity(content),
                    sourceContents.map { contentMapper.mapToEntity(it) }
                )
            }
            .doOnError { e ->
                logger.error("Error in updateSources for content: $content")
                logger.error("Source Content, Begin:")
                sourceContents.forEach {
                    logger.error("$it")
                }
                logger.error("End source content", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun deleteForCollection(
        chapterCollection: Collection,
        typeFilter: ContentType?
    ): Completable {
        val typeId = typeFilter?.let {
            contentTypeDao.fetchId(typeFilter)
        }

        activeConnections.getOrDefault(chapterCollection, null)
            ?.let { it.value?.forEach { chunk -> chunk.draftNumber = -1 } }

        return Completable.fromCallable {
            contentDao.deleteForCollection(
                collectionMapper.mapToEntity(chapterCollection),
                typeId
            )
        }
    }

    override fun delete(obj: Content): Completable {
        return Completable
            .fromAction {
                contentDao.delete(contentMapper.mapToEntity(obj))
            }
            .doOnError { e ->
                logger.error("Error in delete for content: $obj", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getAll(): Single<List<Content>> {
        return Single
            .fromCallable {
                contentDao
                    .fetchAll()
                    .map(this::buildContent)
                    .filter {
                        if (it.bridged) {
                            logger.info("Ignoring bridged content: ${it}")
                        }
                        !it.bridged
                    }
            }
            .doOnError { e ->
                logger.error("Error in getAll", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun insertForCollection(contentList: List<Content>, collection: Collection): Single<List<Content>> {
        return Single
            .fromCallable {
                contentList.forEach { content ->
                    val id = contentDao.insert(
                        contentMapper
                            .mapToEntity(content, collection.id)
                            .apply { collectionFk = collection.id }
                    )
                    content.id = id
                }
                val contentsAfterInsertion = getByCollection(collection).blockingGet()
                activeConnections.getOrDefault(collection, null)
                    ?.accept(contentsAfterInsertion)

                contentsAfterInsertion
            }
            .doOnError { e ->
                logger.error("Error in insertForCollection for collection: $collection", e)
            }
            .subscribeOn(Schedulers.io())
    }

    /**
     * Bulk update. The entities are mapped without a `collectionFk`, which leaves
     * `ContentMapper.mapToEntity`'s default of 0 in the entity — harmless here, because
     * [ContentDao.updateAll] deliberately does not write `collection_fk` (unlike [ContentDao.update],
     * which does, and which is why [update] re-reads the existing row before mapping).
     */
    override fun updateAll(content: List<Content>): Completable {
        return Completable
            .fromAction {
                val entities = content.map { obj ->
                    contentMapper.mapToEntity(obj)
                }
                contentDao.updateAll(entities)
            }
            .doOnError { e ->
                logger.error("Error in updateAll for ${content.size} content row(s)", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun linkDerivedToSource(
        derivedContents: List<Content>,
        sourceContents: List<Content>
    ): Completable {
        return Completable
            .fromAction {
                derivedContents.forEach { derived ->
                    sourceContents.forEach { source ->
                        if (derived.labelKey == source.labelKey && derived.type == source.type) {
                            contentDao.linkDerivative(derived.id, source.id)
                        }
                    }
                }
            }
            .subscribeOn(Schedulers.io())
    }

    override fun update(obj: Content): Completable {
        return Completable
            .fromAction {
                val existing = contentDao.fetchById(obj.id)
                val entity = contentMapper.mapToEntity(obj)
                // Make sure we don't overwrite the collection relationship
                entity.collectionFk = existing.collectionFk
                contentDao.update(entity)

                updateConnection(obj, entity.collectionFk)
            }
            .doOnError { e ->
                logger.error("Error in update for content: $obj", e)
            }
            .subscribeOn(Schedulers.io())
    }

    /**
     * Updates the content stored inside the active connections.
     * Calls this method when making a change to the content in the database
     * to avoid out-of-sync between the database and connections.
     */
    private fun updateConnection(
        newContent: Content,
        collectionId: Int
    ) {
        activeConnections.keys.find { it.id == collectionId }?.let { collection ->
            activeConnections[collection]?.let { connection ->
                connection.value?.find {
                    it.id == newContent.id
                }?.let { contentInRelay ->
                    contentInRelay.apply {
                        sort = newContent.sort
                        labelKey = newContent.labelKey
                        start = newContent.start
                        end = newContent.end
                        selectedTake = newContent.selectedTake
                        text = newContent.text
                        format = newContent.format
                        type = newContent.type
                        draftNumber = newContent.draftNumber
                        bridged = newContent.bridged
                    }
                }
            }
        }
    }

    override suspend fun getAllSuspend(): List<Content> = getAll().await()
    override suspend fun updateSuspend(obj: Content) = update(obj).await()
    override suspend fun deleteSuspend(obj: Content) = delete(obj).await()

    override suspend fun insertForCollectionSuspend(contentList: List<Content>, collection: Collection): List<Content> =
        insertForCollection(contentList, collection).await()

    override suspend fun getByCollectionSuspend(collection: Collection): List<Content> =
        getByCollection(collection).await()

    override fun getByCollectionFlow(collection: Collection): Flow<List<Content>> =
        getByCollectionWithPersistentConnection(collection).asFlow()

    override suspend fun getCollectionMetaContentSuspend(collection: Collection): Content =
        getCollectionMetaContent(collection).await()

    override suspend fun getSourcesSuspend(content: Content): List<Content> =
        getSources(content).await()

    override suspend fun updateSourcesSuspend(content: Content, sourceContents: List<Content>) =
        updateSources(content, sourceContents).await()

    override suspend fun deleteForCollectionSuspend(chapterCollection: Collection, typeFilter: ContentType?) =
        deleteForCollection(chapterCollection, typeFilter).await()

    override suspend fun linkDerivedToSourceSuspend(derivedContents: List<Content>, sourceContents: List<Content>) =
        linkDerivedToSource(derivedContents, sourceContents).await()

    override suspend fun updateAllSuspend(content: List<Content>) =
        updateAll(content).await()

    private fun buildContent(entity: ContentEntity): Content {
        // Check for sources
        val sources = contentDao.fetchSources(entity)
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
}
