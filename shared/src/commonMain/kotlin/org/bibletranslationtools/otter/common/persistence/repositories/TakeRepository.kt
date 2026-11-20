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

import kotlinx.coroutines.rx2.rxSingle
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.rxCompletable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.TakeEntity
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.CollectionMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.MarkerMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.TakeMapper
import java.io.File
import java.time.LocalDate

class TakeRepository(
    private val database: DaoProvider,
    private val markerMapper: MarkerMapper,
    private val collectionMapper: CollectionMapper
) : ITakeRepository {

    private val logger = LoggerFactory.getLogger(TakeRepository::class.java)
    private val takeMapper = TakeMapper(database.checkingStatusDao)

    private val takeDao = database.takeDao
    private val markerDao = database.markerDao
    private val contentDao = database.contentDao
    private val contentTypeDao = database.contentTypeDao

    /** Delete the DB record. Instead of this, consider using {@see markDeleted} to set the deleted timestamp. */
    override fun delete(obj: Take): Completable {
        return Completable
            .fromAction {
                takeDao.delete(takeMapper.mapToEntity(obj))
            }
            .doOnError { e ->
                logger.error("Error in delete with take: $obj", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun deleteForContent(content: Content): Completable = rxCompletable {
        deleteForContentSuspend(content)
    }

    /** Set the deleted timestamp to now. */
    override fun markDeleted(take: Take): Completable {
        return Completable
            .fromAction {
                val withDeletionFlag = take.copy(deleted = LocalDate.now())
                takeDao.update(takeMapper.mapToEntity(withDeletionFlag))
            }
            .doOnError { e ->
                logger.error("Error in markDeleted with take: $take", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getAll(): Single<List<Take>> = rxSingle { getAllSuspend() }

    override fun getByContent(content: Content, includeDeleted: Boolean): Single<List<Take>> =
        rxSingle { getByContentSuspend(content, includeDeleted) }

    override fun insertForContent(take: Take, content: Content): Single<Int> =
        rxSingle { insertForContentSuspend(take, content) }

    override fun update(obj: Take): Completable = rxCompletable { updateSuspend(obj) }

    override suspend fun removeNonExistentTakesSuspend() = withContext(Dispatchers.IO) {
        runCatching {
            database.transaction {
                val takes = takeDao.fetchAll()
                for (take in takes) {
                    val takeFile = File(take.filepath)
                    if (!takeFile.exists()) {
                        // Take does not exist anymore
                        // Reset the selected take if necessary to satisfy foreign key constraints
                        val content = contentDao.fetchById(take.contentFk)
                        if (content.selectedTakeFk == take.id) content.selectedTakeFk = null
                        contentDao.update(content)
                        // Remove the take from the database
                        takeDao.delete(take)
                    }
                }
            }
        }
            .onFailure { logger.error("Error in removeNonExistentTakes", it) }
            .getOrThrow()
    }

    override fun getSoftDeletedTakes(project: Collection): Single<List<Take>> {
        return Single
            .fromCallable {
                takeDao.fetchSoftDeletedTakes(collectionMapper.mapToEntity(project))
                    .map(this::buildTake)
            }
            .doOnError { e ->
                logger.error("Error in getSoftDeletedTakes for collection: $project", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun deleteExpiredTakes(expiry: Int): Completable =
        rxCompletable { deleteExpiredTakesSuspend(expiry) }

    override suspend fun deleteExpiredTakesSuspend(expiry: Int) = withContext(Dispatchers.IO) {
        val takes = takeDao.fetchSoftDeletedTakes()
        val expired = LocalDate.now().minusDays(expiry.toLong())
        logger.info("Deleting soft deleted takes on or before: $expired")
        takes.forEach { take ->
            take.deletedTs?.let { timestamp ->
                val parsed = LocalDate.parse(timestamp)
                if (parsed.isBefore(expired) || parsed.isEqual(expired)) {
                    logger.info("Deleting: ${take.filename}, soft delete date is: ${take.deletedTs}")
                    File(take.filepath).delete()
                    takeDao.delete(take)
                }
            }
        }
    }

    override fun getByCollection(chapterCollection: Collection, includeDeleted: Boolean): Single<List<Take>> {
        return Single
            .fromCallable {
                takeDao
                    .fetchByCollectionId(chapterCollection.id, includeDeleted)
                    .map(this::buildTake)
            }
            .doOnError { e ->
                logger.error("Error in getByCollection for collection: $chapterCollection, includeDeleted: $includeDeleted", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getContentType(take: Take): Single<ContentType> {
        return Single
            .fromCallable {
                val contentId = takeDao.fetchById(take.id).contentFk
                val content = contentDao.fetchById(contentId)
                contentTypeDao.fetchForId(content.type_fk)
            }
    }

    override suspend fun getAllSuspend(): List<Take> = withContext(Dispatchers.IO) {
        runCatching {
            takeDao
                .fetchAll()
                .map(::buildTake)
        }
            .onFailure { e ->
                logger.error("Error in getAll", e)
            }
            .getOrThrow()
    }
    override suspend fun updateSuspend(obj: Take) {
        withContext(Dispatchers.IO) {
            runCatching {
                val existing = takeDao.fetchById(obj.id)
                val entity = takeMapper.mapToEntity(obj, existing.contentFk)
                takeDao.update(entity)

                // Delete and replace markers
                markerDao
                    .fetchByTakeId(obj.id)
                    .forEach {
                        markerDao.delete(it)
                    }

                obj.markers.forEach {
                    val markerEntity = markerMapper.mapToEntity(it, obj.id)
                    markerEntity.id = 0
                    markerDao.insert(markerEntity)
                }
            }
                .onFailure { e ->
                    logger.error("Error in update for take: $obj", e)
                }
                .getOrThrow()
        }
    }
    override suspend fun deleteSuspend(obj: Take) = delete(obj).await()

    override suspend fun insertForContentSuspend(take: Take, content: Content): Int = withContext(Dispatchers.IO) {
        runCatching {
            val takeId = takeDao.insert(takeMapper.mapToEntity(take, content.id))
            // Insert the markers
            take.markers.forEach {
                val entity = markerMapper.mapToEntity(it, takeId)
                entity.id = 0
                markerDao.insert(entity)
            }
            takeId
        }
            .onFailure { e ->
                logger.error("Error in insertForContent with take: $take and content $content", e)
            }
            .getOrThrow()
    }

    override suspend fun getByContentSuspend(content: Content, includeDeleted: Boolean): List<Take> = withContext(Dispatchers.IO) {
        runCatching {
            takeDao
                .fetchByContentId(content.id, includeDeleted)
                .map(::buildTake)
        }
            .onFailure { e ->
                logger.error("Error in getByContent for content: $content, includeDeleted: $includeDeleted", e)
            }
            .getOrThrow()
    }

    override suspend fun deleteForContentSuspend(content: Content) {
        withContext(Dispatchers.IO) {
            runCatching {
                takeDao
                    .fetchByContentId(content.id, true)
                    .forEach {
                        takeDao.delete(it)
                    }
            }
                .onFailure { e ->
                    logger.error("Error in deleteForContent: $content\n", e)
                }
                .getOrThrow()
        }
    }

    override suspend fun markDeletedSuspend(take: Take) =
        markDeleted(take).await()

    override suspend fun getSoftDeletedTakesSuspend(project: Collection): List<Take> =
        getSoftDeletedTakes(project).await()


    override suspend fun getByCollectionSuspend(chapterCollection: Collection, includeDeleted: Boolean): List<Take> =
        getByCollection(chapterCollection, includeDeleted).await()

    override suspend fun getContentTypeSuspend(take: Take): ContentType =
        getContentType(take).await()

    private fun buildTake(entity: TakeEntity): Take {
        val markers = markerDao
            .fetchByTakeId(entity.id)
            .map { markerMapper.mapFromEntity(it) }
        return takeMapper.mapFromEntity(entity, markers)
    }
}
