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
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.ProjectCompletionStatus
import org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceAudioAccessor
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.bibletranslationtools.otter.common.persistence.entities.WorkbookDescriptorEntity

class WorkbookDescriptorRepository(
    database: IAppDatabase,
    private val collectionRepository: ICollectionRepository,
    private val contentRepository: IContentRepository,
    private val workbookRepository: IWorkbookRepository,
    private val projectCompletionStatus: ProjectCompletionStatus
) : IWorkbookDescriptorRepository {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val workbookDescriptorDao = database.workbookDescriptorDao
    private val workbookTypeDao = database.workbookTypeDao

    override fun getById(id: Int): Maybe<WorkbookDescriptor> {
        return Maybe
            .fromCallable {
                workbookDescriptorDao.fetchById(id)
            }
            .map { entity ->
                val target = collectionRepository.getProject(entity.targetFk).blockingGet()
                val source = collectionRepository.getProject(entity.sourceFk).blockingGet()
                buildWorkbookDescriptor(entity, source, target, sourceAudioFor(listOf(source)), HashMap())
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getAll(computeSourceAudio: Boolean): Single<List<WorkbookDescriptor>> {
        return Single
            .fromCallable {
                // Batch-resolve every referenced source/target collection in ~3 queries (vs ~3 per
                // descriptor). With many projects the per-descriptor getProject chain (collection +
                // dublin_core + language fetchById, on one SQLite connection) dominated the home-page
                // load. When [computeSourceAudio] is true, source audio is resolved by opening each
                // unique source resource container (a zip) ONCE; that RC open is still ~hundreds of ms
                // each, so callers that render a list first (Orature home) pass false and resolve it
                // later via [getSourceAudioSuspend], off the critical path.
                val entities = workbookDescriptorDao.fetchAll()
                val projectIds = entities.flatMap { listOf(it.sourceFk, it.targetFk) }
                val projectsById = collectionRepository.getProjects(projectIds).blockingGet()
                val sourceAudioCache: Map<String, Boolean> =
                    if (computeSourceAudio) sourceAudioFor(entities.mapNotNull { projectsById[it.sourceFk] })
                    else emptyMap()
                val typeCache = HashMap<Int, ProjectMode>()
                entities.mapNotNull { entity ->
                    val source = projectsById[entity.sourceFk] ?: return@mapNotNull null
                    val target = projectsById[entity.targetFk] ?: return@mapNotNull null
                    buildWorkbookDescriptor(entity, source, target, sourceAudioCache, typeCache)
                }
            }
            .subscribeOn(Schedulers.io())
            .doOnError {
                logger.error("Error getting workbook descriptors.", it)
            }
    }

    /** Resolve hasSourceAudio (descriptorId -> has) for the given descriptors, opening each unique
     *  source resource container once. Runs on the IO scheduler. */
    private fun resolveSourceAudio(descriptors: List<WorkbookDescriptor>): Single<Map<Int, Boolean>> {
        return Single
            .fromCallable {
                val byKey = sourceAudioFor(descriptors.map { it.sourceCollection })
                descriptors.associate { d ->
                    val rc = d.sourceCollection.resourceContainer
                    d.id to (rc?.let { byKey["${it.path}|${d.sourceCollection.slug}"] } ?: false)
                }
            }
            .subscribeOn(Schedulers.io())
            .doOnError { logger.error("Error resolving source audio.", it) }
    }

    /** Resolve hasSourceAudio for a set of source collections, opening each unique resource container
     *  (zip) only ONCE. Keyed by "rcPath|slug". */
    private fun sourceAudioFor(sources: List<Collection>): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        sources
            .filter { it.resourceContainer != null }
            .distinctBy { "${it.resourceContainer!!.path}|${it.slug}" }
            .groupBy { it.resourceContainer!!.path }
            .forEach { (_, group) ->
                val meta = group.first().resourceContainer!!
                val results = SourceAudioAccessor.hasSourceAudio(meta, group.map { it.slug })
                group.forEach { out["${meta.path}|${it.slug}"] = results[it.slug] ?: false }
            }
        return out
    }

    override fun delete(list: List<WorkbookDescriptor>): Completable {
        return Completable
            .fromAction {
                list
                    .map(::mapToEntity)
                    .forEach {
                        workbookDescriptorDao.delete(it)
                    }
            }
            .subscribeOn(Schedulers.io())
            .doOnError {
                logger.error("Error deleting workbook descriptors.", it)
            }
    }

    private fun buildWorkbookDescriptor(
        entity: WorkbookDescriptorEntity,
        sourceCollection: Collection,
        targetCollection: Collection,
        // Per-batch memo caches (getAll passes shared maps; getById passes fresh ones, so a single
        // lookup behaves exactly as before). hasSourceAudio opens the source resource container (a
        // zip) and the same source RC + type recur across descriptors.
        sourceAudioCache: Map<String, Boolean>,
        typeCache: MutableMap<Int, ProjectMode>
    ): WorkbookDescriptor {
        val sourceRc = sourceCollection.resourceContainer!!
        // Read from the prefilled cache only — never open the RC here (that zip open is the expensive
        // part callers may defer). Absent → false until resolved via getSourceAudioSuspend.
        val hasSourceAudio = sourceAudioCache["${sourceRc.path}|${sourceCollection.slug}"] ?: false
        val mode = typeCache.getOrPut(entity.typeFk) { workbookTypeDao.fetchById(entity.typeFk)!! }
        val progress = Single
            .fromCallable {
                getProgress(sourceCollection, targetCollection, mode)
            }
            .subscribeOn(Schedulers.io())

        return WorkbookDescriptor(
            entity.id,
            sourceCollection,
            targetCollection,
            mode,
            progress,
            hasSourceAudio
        )
    }

    private fun getProgress(
        source: Collection,
        target: Collection,
        mode: ProjectMode
    ): Double {
        return when (mode) {
            ProjectMode.TRANSLATION -> {
                calculateTranslationProgress(target)
            }
            ProjectMode.NARRATION, ProjectMode.DIALECT -> {
                calculateNarrationProgress(source, target)
            }
        }
    }

    private fun calculateTranslationProgress(target: Collection): Double {
        val chapters = collectionRepository.getChildren(target)
            .flattenAsObservable { it }
            .flatMapSingle { chapter ->
                contentRepository.getCollectionMetaContent(chapter)
            }
            .blockingIterable().toList()

        return chapters.count { it.selectedTake != null }.toDouble() / chapters.size
    }

    private fun calculateNarrationProgress(
        source: Collection,
        target: Collection
    ): Double {
        val workbook = workbookRepository.get(source, target)
        return if (workbook.projectFilesAccessor.isInitialized()) {
            val chapterProgress = workbook.target.chapters
                .toList()
                .blockingGet()
                .map {
                    projectCompletionStatus.getChapterNarrationProgress(workbook, it)
                }

            chapterProgress.count { it == 1.0 }.toDouble() / chapterProgress.size
        } else {
            0.0
        }
    }

    override suspend fun getByIdSuspend(id: Int): WorkbookDescriptor? = getById(id).awaitSingleOrNull()
    override suspend fun getAllSuspend(computeSourceAudio: Boolean): List<WorkbookDescriptor> =
        getAll(computeSourceAudio).await()
    override suspend fun getSourceAudioSuspend(descriptors: List<WorkbookDescriptor>): Map<Int, Boolean> =
        resolveSourceAudio(descriptors).await()
    override suspend fun deleteSuspend(list: List<WorkbookDescriptor>) = delete(list).await()

    private fun mapToEntity(obj: WorkbookDescriptor): WorkbookDescriptorEntity {
        return WorkbookDescriptorEntity(
            obj.id,
            obj.sourceCollection.id,
            obj.targetCollection.id,
            workbookTypeDao.fetchId(obj.mode)
        )
    }
}