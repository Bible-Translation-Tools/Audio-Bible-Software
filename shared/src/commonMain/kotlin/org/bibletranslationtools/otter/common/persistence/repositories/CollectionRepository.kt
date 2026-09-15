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
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.mapper.mapToMetadata
import org.bibletranslationtools.otter.common.api.persistence.IResourceContainerDirectories
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.WorkbookDescriptorEntity
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.CollectionMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.LanguageMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.ResourceMetadataMapper
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Checking
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.resourcecontainer.entity.dublincore
import org.wycliffeassociates.resourcecontainer.entity.project
import java.io.File
import java.lang.Exception
import java.time.LocalDate
import java.time.LocalDateTime

class CollectionRepository(
    private val database: DaoProvider,
    private val directoryProvider: IResourceContainerDirectories,
    private val collectionMapper: CollectionMapper,
    private val metadataMapper: ResourceMetadataMapper,
    private val languageMapper: LanguageMapper
) : ICollectionRepository {

    val log = LoggerFactory.getLogger(CollectionRepository::class.java)

    private val dublinCoreCreator: String = "OratureInfo.SUITE_NAME"
    private val collectionDao = database.collectionDao
    private val contentDao = database.contentDao
    private val metadataDao = database.resourceMetadataDao
    private val languageDao = database.languageDao
    private val resourceMetadataDao = database.resourceMetadataDao
    private val takeDao = database.takeDao
    private val resourceLinkDao = database.resourceLinkDao
    private val workbookTypeDao = database.workbookTypeDao


    override fun delete(obj: Collection): Completable {
        return Completable
            .fromAction {
                collectionDao.delete(collectionMapper.mapToEntity(obj))
            }
            .doOnError { e ->
                log.error("Error in delete for collection $obj", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun deleteProject(project: Collection, deleteAudio: Boolean): Completable {
        return Completable
            .fromAction {
                // 1. Delete the project collection from the database. The associated chunks, takes, and links
                //    should be cascade deleted
                collectionDao.delete(collectionMapper.mapToEntity(project))
                // 2. Load the resource container
                val metadata = project.resourceContainer
                if (metadata != null) {
                    try {
                        ResourceContainer.load(metadata.path).use { container ->
                            // 3. Remove the project from the manifest
                            container.manifest.projects = container
                                .manifest
                                .projects
                                .filter { it.identifier != project.slug }
                            // 4a. If the manifest has more projects, write out the new manifest
                            if (container.manifest.projects.isNotEmpty()) {
                                container.writeManifest()
                            } else {
                                // 4b. If the manifest has no projects left,
                                // delete the RC folder and the metadata from the database
                                metadata.path.deleteRecursively()
                                metadataDao.delete(metadataMapper.mapToEntity(metadata))
                            }
                        }
                    } catch (e: Exception) {
                        log.info("Delete project - Manifest doesn't exist, no changes committed for ${project.slug}")
                    }
                }
            }
            .doOnError { e ->
                log.error("Error in delete project, collection: $project, deleteAudio: $deleteAudio", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun deleteResources(project: Collection, deleteAudio: Boolean): Completable {
        return Completable
            .fromAction {
                database.transaction {
                    takeDao.deleteResourceTakesForProject(project.id, project.slug)
                }
            }
            .doOnError { e ->
                log.error("Error in deleteResources for collection: $project, deleteAudio: $deleteAudio", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun collectionsWithoutTakes(project: Collection): Single<List<Collection>> {
        return Single.fromCallable {
            collectionDao
                .collectionsWithoutTakes(collectionMapper.mapToEntity(project))
                .map {
                    val collection = collectionMapper.mapFromEntity(it, project.resourceContainer)
                    collection
                }
        }
    }

    override suspend fun getAllSuspend(): List<Collection> = getAll().await()
    override suspend fun updateSuspend(obj: Collection) = update(obj).await()
    override suspend fun deleteSuspend(obj: Collection) = delete(obj).await()

    override suspend fun insertSuspend(collection: Collection): Int = insert(collection).await()
    override suspend fun getProjectSuspend(id: Int): Collection? = getProject(id).awaitSingleOrNull()
    override suspend fun getDerivedProjectSuspend(sourceProject: Collection): Collection? =
        getDerivedProject(sourceProject).awaitSingleOrNull()

    override suspend fun getDerivedProjectsSuspend(): List<Collection> = getDerivedProjects().await()
    override suspend fun getSourceProjectsSuspend(): List<Collection> = getSourceProjects().await()
    override suspend fun getRootSourcesSuspend(): List<Collection> = getRootSources().await()
    override suspend fun getSourceSuspend(project: Collection): Collection? = getSource(project).awaitSingleOrNull()
    override suspend fun getChildrenSuspend(collection: Collection): List<Collection> = getChildren(collection).await()
    override suspend fun getProjectBySlugAndMetadataSuspend(slug: String, metadata: ResourceMetadata): Collection =
        getProjectBySlugAndMetadata(slug, metadata).await()

    override suspend fun updateSourceSuspend(collection: Collection, newSource: Collection) =
        updateSource(collection, newSource).await()

    override suspend fun updateParentSuspend(collection: Collection, newParent: Collection) =
        updateParent(collection, newParent).await()

    override suspend fun deriveProjectSuspend(
        sourceMetadatas: List<ResourceMetadata>,
        sourceCollection: Collection,
        language: Language,
        verseByVerse: Boolean,
        mode: ProjectMode
    ): Collection = deriveProject(sourceMetadatas, sourceCollection, language, verseByVerse, mode).await()

    override suspend fun deriveProjectsSuspend(
        rootCollection: Collection,
        language: Language,
        verseByVerse: Boolean,
        mode: ProjectMode
    ): List<Collection> = deriveProjects(rootCollection, language, verseByVerse, mode).await()

    override suspend fun deleteProjectSuspend(project: Collection, deleteAudio: Boolean) =
        deleteProject(project, deleteAudio).await()

    override suspend fun deleteResourcesSuspend(project: Collection, deleteAudio: Boolean) =
        deleteResources(project, deleteAudio).await()

    override suspend fun collectionsWithoutTakesSuspend(project: Collection): List<Collection> =
        collectionsWithoutTakes(project).await()

    override fun getAll(): Single<List<Collection>> {
        return Single
            .fromCallable {
                collectionDao
                    .fetchAll()
                    .map(this::buildCollection)
            }
            .doOnError { e ->
                log.error("Error in getAll", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getDerivedProject(sourceProject: Collection): Maybe<Collection> {
        return Maybe
            .fromCallable {
                collectionDao
                    .fetchByLabel("project")
                    .find { it.sourceFk == sourceProject.id }
                    ?.let(this::buildCollection)!!
            }
            .doOnError { e ->
                if (e !is java.lang.NullPointerException) {
                    log.error("Error in getDerivedProject", e)
                }
            }
            .onErrorComplete()
            .subscribeOn(Schedulers.io())
    }

    override fun getDerivedProjects(): Single<List<Collection>> {
        return Single
            .fromCallable {
                collectionDao
                    .fetchByLabel("project")
                    .filter { it.sourceFk != null }
                    .map(this::buildCollection)
            }
            .doOnError { e ->
                log.error("Error in getDerivedProjects", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getProject(id: Int): Maybe<Collection> {
        return Maybe
            .fromCallable {
                buildCollection(
                    collectionDao.fetchById(id)
                )
            }
            .doOnError { e ->
                log.error("Error in getProject, id: $id", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getProjects(ids: List<Int>): Single<Map<Int, Collection>> {
        return Single
            .fromCallable { buildCollections(ids) }
            .doOnError { e -> log.error("Error in getProjects", e) }
            .subscribeOn(Schedulers.io())
    }

    /**
     * Build many collections in ~3 queries instead of ~3-per-id. Batch-fetch the collection rows,
     * then their (shared) dublin_core metadata rows, then those metadata's (shared) language rows,
     * and assemble in memory with the same mappers [buildCollection] uses. Returns id -> Collection
     * (missing ids simply absent).
     */
    private fun buildCollections(ids: List<Int>): Map<Int, Collection> {
        if (ids.isEmpty()) return emptyMap()
        val collectionEntities = collectionDao.fetchByIds(ids.distinct())
        val metadataIds = collectionEntities.mapNotNull { it.dublinCoreFk }.distinct()
        val metadataEntities = resourceMetadataDao.fetchByIds(metadataIds)
        val languageIds = metadataEntities.map { it.languageFk }.distinct()
        val languageById = languageDao.fetchByIds(languageIds).associateBy { it.id }

        val metadataById = metadataEntities.associate { me ->
            val language = languageMapper.mapFromEntity(languageById.getValue(me.languageFk))
            me.id to metadataMapper.mapFromEntity(me, language)
        }
        return collectionEntities.associate { ce ->
            val metadata = ce.dublinCoreFk?.let { metadataById[it] }
            ce.id to collectionMapper.mapFromEntity(ce, metadata)
        }
    }

    override fun getSourceProjects(): Single<List<Collection>> {
        return Single
            .fromCallable {
                collectionDao
                    .fetchByLabel("project")
                    .filter { it.sourceFk == null }
                    .map(this::buildCollection)
            }
            .doOnError { e ->
                log.error("Error in getSourceProjects", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getRootSources(): Single<List<Collection>> {
        return Single
            .fromCallable {
                collectionDao
                    .fetchAll()
                    .filter { it.parentFk == null && it.sourceFk == null }
                    .map(this::buildCollection)
            }
            .doOnError { e ->
                log.error("Error in getRootSources", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getSource(project: Collection): Maybe<Collection> {
        return Maybe
            .fromCallable {
                collectionDao.fetchSource(collectionDao.fetchById(project.id))?.let {
                    buildCollection(it)
                }
            }
            .doOnError { e ->
                log.error("Error in getSource for collection: $project", e)
            }
            .onErrorComplete()
            .subscribeOn(Schedulers.io())
    }

    override fun getChildren(collection: Collection): Single<List<Collection>> {
        return Single
            .fromCallable {
                collectionDao
                    .fetchChildren(collectionMapper.mapToEntity(collection))
                    .map(this::buildCollection)
            }
            .doOnError { e ->
                log.error("Error in getChildren for collection: $collection", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getProjectBySlugAndMetadata(slug: String, metadata: ResourceMetadata): Single<Collection> {
        return Single
            .fromCallable {
                collectionDao.fetch(slug, metadata.id)?.let {
                    buildCollection(it)
                } ?: throw NullPointerException(
                    "A collection matching slug: $slug and metadata: [$metadata] was not found."
                )
            }
            .doOnError { e ->
                log.error("Error in getProjectBySlugAndMetadata for slug: $slug and metadata $metadata", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun updateSource(collection: Collection, newSource: Collection): Completable {
        return Completable
            .fromAction {
                val entity = collectionDao.fetchById(collection.id)
                entity.sourceFk = newSource.id
                collectionDao.update(entity)
            }
            .doOnError { e ->
                log.error("Error in update source for collection: $collection, new source: $newSource", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun updateParent(collection: Collection, newParent: Collection): Completable {
        return Completable
            .fromAction {
                val entity = collectionDao.fetchById(collection.id)
                entity.parentFk = newParent.id
                collectionDao.update(entity)
            }
            .doOnError { e ->
                log.error("Error in updateParent for collection: $collection, new parent: $collection", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun insert(collection: Collection): Single<Int> {
        return Single
            .fromCallable {
                collectionDao.insert(collectionMapper.mapToEntity(collection))
            }
            .doOnError { e ->
                log.error("Error in insert for collection: $collection", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun update(obj: Collection): Completable {
        return Completable
            .fromAction {
                val entity = collectionDao.fetchById(obj.id)
                val newEntity = collectionMapper.mapToEntity(obj, entity.parentFk, entity.sourceFk)
                collectionDao.update(newEntity)
            }
            .doOnError { e ->
                log.error("Error in update for collection: $obj", e)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun deriveProjects(
        rootCollection: Collection,
        language: Language,
        verseByVerse: Boolean,
        mode: ProjectMode
    ): Single<List<Collection>> {
        return getChildren(rootCollection)
            .flattenAsObservable {
                it
            }
            .map { sourceCollection ->
                val sourceMetadata = sourceCollection.resourceContainer!!
                deriveProject(
                    listOf(sourceMetadata),
                    sourceCollection,
                    language,
                    verseByVerse,
                    mode
                ).blockingGet()
            }
            .toList()
    }

    override fun deriveProject(
        sourceMetadatas: List<ResourceMetadata>,
        sourceCollection: Collection,
        language: Language,
        verseByVerse: Boolean,
        mode: ProjectMode
    ): Single<Collection> {
        return Single
            .fromCallable {
                database.transactionResult {

                    val derivedMetadata = deriveAndLinkMetadata(sourceMetadatas, language)
                    val mainDerivedMetadata = derivedMetadata.first()

                    val sourceCollectionEntity = collectionDao.fetchById(sourceCollection.id)
                    // Try to find existent project
                    var projectEntity = findProjectCollection(sourceCollectionEntity, mainDerivedMetadata)
                    projectEntity?.let {
                        it.modifiedTs = LocalDateTime.now().toString()
                        collectionDao.update(it)
                    }

                    if (projectEntity == null) {
                        // Insert the derived project
                        projectEntity = deriveProjectCollection(sourceCollectionEntity, mainDerivedMetadata)

                        // Copy the chapters
                        copyChapters(sourceCollectionEntity.id, projectEntity.id, mainDerivedMetadata.id)

                        val metadataSourceToDerivedMap = sourceMetadatas.zip(derivedMetadata).associate { it }
                        copyResourceLinks(projectEntity, metadataSourceToDerivedMap)

                        // Add a project to the container if necessary
                        // Load the existing resource container and see if we need to add another project
                        ResourceContainer.load(File(mainDerivedMetadata.path)).use { container ->
                            if (container.manifest.projects.none { it.identifier == sourceCollection.slug }) {
                                container.manifest.projects = container.manifest.projects.plus(
                                    project {
                                        sort = if (
                                            mainDerivedMetadata.subject.lowercase() == "bible" &&
                                            projectEntity.sort > 39
                                        ) {
                                            projectEntity.sort + 1
                                        } else {
                                            projectEntity.sort
                                        }
                                        identifier = projectEntity.slug
                                        path = "./${projectEntity.slug}"
                                        // This title will not be localized into the target language
                                        title = projectEntity.title
                                        // Unable to get categories and versification from the source collection
                                    }
                                )
                                // Update the container
                                container.write()
                            }
                        }
                    }

                    val workbookDescriptor = database.workbookDescriptorDao.fetch(
                        sourceCollection.id,
                        projectEntity.id,
                        workbookTypeDao.fetchId(mode)
                    )
                    if (workbookDescriptor == null) {
                        // copy the content under chapter-level
                        if (verseByVerse) {
                            copyContent(sourceCollectionEntity.id, mainDerivedMetadata.id)
                            linkDerivativeContent(sourceCollectionEntity.id, projectEntity.id)
                        } else {
                            copyMetaContent(sourceCollectionEntity.id, mainDerivedMetadata.id)
                        }
                        insertWorkbookDescriptor(sourceCollection.id, projectEntity.id, mode)
                    }


                    return@transactionResult collectionMapper.mapFromEntity(
                        projectEntity,
                        metadataMapper.mapFromEntity(mainDerivedMetadata, language)
                    )
                }
            }
            .doOnError { e ->
                log.error("Error in deriveProject for source collection: $sourceCollection, language: $language")
                log.error("With:")
                sourceMetadatas.forEach {
                    log.error("Metadata: $it")
                }
                log.error("End Metadata", e)
            }
            .subscribeOn(Schedulers.io())
    }

    private fun findProjectCollection(
        sourceEntity: CollectionEntity,
        derivedMetadata: ResourceMetadataEntity
    ): CollectionEntity? {
        return collectionDao.fetch(
            slug = sourceEntity.slug,
            containerId = derivedMetadata.id
        )
    }

    private fun deriveProjectCollection(
        sourceEntity: CollectionEntity,
        derivedMetadata: ResourceMetadataEntity
    ): CollectionEntity {
        return sourceEntity
            .copy(
                id = 0,
                parentFk = null,
                dublinCoreFk = derivedMetadata.id,
                sourceFk = sourceEntity.id
            )
            .let { derivedProject ->
                derivedProject.modifiedTs = LocalDateTime.now().toString()
                val id = collectionDao.insert(derivedProject)
                derivedProject.copy(id = id)
            }
    }

    private fun deriveAndLinkMetadata(
        sourceMetadatas: List<ResourceMetadata>,
        newLanguage: Language
    ): List<ResourceMetadataEntity> {
        val derivedMetadata = sourceMetadatas.map {
            findOrInsertMetadataEntity(it, newLanguage)
        }

        val mainDerived = derivedMetadata.first()
        val linkDerived = derivedMetadata.drop(1)
        linkDerived.forEach {
            resourceMetadataDao.addLink(mainDerived.id, it.id)
        }
        return derivedMetadata
    }

    private fun findOrInsertMetadataEntity(
        source: ResourceMetadata,
        language: Language
    ): ResourceMetadataEntity {
        // Check for existing resource containers
        val existingMetadata = metadataDao.fetchAll()
        val matches = existingMetadata.filter {
            it.identifier == source.identifier &&
                    it.languageFk == language.id &&
                    it.creator == dublinCoreCreator &&
                    it.version == source.version &&
                    it.derivedFromFk == source.id
        }

        val metadataEntity = if (matches.isEmpty()) {
            // This combination of identifier and language does not already exist; create it
            createResourceContainer(source, language).use { container ->
                // Convert DublinCore to ResourceMetadata
                val metadata = container.manifest.dublinCore
                    .mapToMetadata(container.file, language)

                // Insert ResourceMetadata into database
                val entity = metadataMapper.mapToEntity(metadata)
                entity.derivedFromFk = source.id
                entity.id = metadataDao.insert(entity)
                entity
            }
        } else {
            // Use the existing metadata
            // Will throw an exception if the list has more than one element
            matches.single()
        }
        return metadataEntity
    }

    private fun createResourceContainer(source: ResourceMetadata, targetLanguage: Language): ResourceContainer {
        val derivedContainerType = when (source.type) {
            ContainerType.Bundle -> ContainerType.Book // Sources can be bundles, but not our derived containers.
            else -> source.type
        }
        val dublinCore = dublincore {
            identifier = source.identifier
            issued = LocalDate.now().toString()
            modified = LocalDate.now().toString()
            language = org.wycliffeassociates.resourcecontainer.entity.language {
                identifier = targetLanguage.slug
                direction = targetLanguage.direction
                title = targetLanguage.name
            }
            creator = dublinCoreCreator
            version = source.version
            rights = source.license
            format = MimeType.of(source.format).norm
            subject = source.subject
            type = derivedContainerType.slug
            title = source.title
        }
        val directory = directoryProvider.getDerivedContainerDirectory(
            // A placeholder file is needed here for the mapping function
            // The file is never used, since the DP doesn't look at the directory
            // to generate the derived directory.
            dublinCore.mapToMetadata(File("."), targetLanguage),
            source
        )
        val container = ResourceContainer.create(directory) {
            // Set up the manifest
            manifest = Manifest(
                dublinCore,
                listOf(),
                Checking()
            )
        }
        container.write()
        return container
    }

    private fun copyChapters(sourceId: Int, projectId: Int, metadataId: Int) {
        collectionDao.copyChapters(sourceId, projectId, metadataId)
    }

    private fun copyContent(sourceId: Int, metadataId: Int) {
        contentDao.copyContent(sourceId, metadataId)
    }

    private fun copyMetaContent(sourceId: Int, metadataId: Int) {
        contentDao.copyMetaContent(sourceId, metadataId)
    }

    private fun copyResourceLinks(
        project: CollectionEntity,
        metadataSourceToDerived: Map<ResourceMetadata, ResourceMetadataEntity>
    ) {
        metadataSourceToDerived.forEach { (sourceMetadata, derivedMetadata) ->
            resourceLinkDao.copyResourceLinks(
                sourceMetadataId = sourceMetadata.id,
                derivedMetadataId = derivedMetadata.id,
                projectId = project.id,
                projectDublinCoreFk = project.dublinCoreFk ?: -1
            )
        }
    }

    private fun linkDerivativeContent(sourceId: Int, projectId: Int) {
        contentDao.linkDerivativeContent(sourceId, projectId)
    }

    private fun insertWorkbookDescriptor(
        sourceCollectionId: Int,
        targetCollectionId: Int,
        mode: ProjectMode
    ) {
        try {
            database.workbookDescriptorDao.insert(
                WorkbookDescriptorEntity(
                    0,
                    sourceCollectionId,
                    targetCollectionId,
                    workbookTypeDao.fetchId(mode)
                )
            )
        } catch (_: Exception) { /* ignore duplicate */ }
    }

    private fun buildCollection(entity: CollectionEntity): Collection {
        var metadata: ResourceMetadata? = null
        entity.dublinCoreFk?.let {
            val metadataEntity = metadataDao.fetchById(it)!!
            val languageEntity = languageDao.fetchById(metadataEntity.languageFk)!!

            val language = languageMapper.mapFromEntity(languageEntity)
            metadata = metadataMapper.mapFromEntity(metadataEntity, language)
        }

        return collectionMapper.mapFromEntity(entity, metadata)
    }
}
