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

import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionOrder
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import java.io.File
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionFingerprint
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.rx2.await
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.collections.OtterTreeNode
import org.bibletranslationtools.otter.common.data.primitives.*
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.domain.mapper.mapToMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DeleteResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportException
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.castOrFindImportException
import org.bibletranslationtools.otter.common.api.persistence.repositories.*
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.CollectionMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.ContentMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.LanguageMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.ResourceMetadataMapper
import org.wycliffeassociates.resourcecontainer.ResourceContainer

class ResourceContainerRepository(
    private val database: DaoProvider,
    private val collectionRepository: ICollectionRepository,
    private val contentRepository: IContentRepository,
    private val resourceRepository: IResourceRepository,
    private val resourceMetadataRepository: IResourceMetadataRepository
) : IResourceContainerRepository {
    private val logger = LoggerFactory.getLogger(ResourceContainerRepository::class.java)

    private val collectionDao = database.collectionDao
    private val contentDao = database.contentDao
    private val contentTypeDao = database.contentTypeDao
    private val resourceMetadataDao = database.resourceMetadataDao
    private val languageDao = database.languageDao
    private val resourceLinkDao = database.resourceLinkDao
    private val contentMapper = ContentMapper(contentTypeDao)

    override fun importResourceContainer(
        rc: ResourceContainer,
        rcTree: OtterTree<CollectionOrContent>,
        languageSlug: String,
        fingerprint: EditionFingerprint?
    ): Single<ImportResult> {
        val dublinCore = rc.manifest.dublinCore
        return Completable
            .fromAction {
                database.transaction {
                    val languageEntity = languageDao.fetchBySlug(languageSlug)
                        ?: throw NullPointerException("Could not find language with slug $languageSlug.")
                    val language = LanguageMapper().mapFromEntity(languageEntity)
                    val metadata = dublinCore.mapToMetadata(rc.file, language)
                        .let {
                            insertMetadataOrThrow(it, fingerprint)
                        }
                    fingerprint?.let { database.storeEditionFingerprint(metadata.id, it) }

                    val relatedDublinCoreIds: List<Int> =
                        linkRelatedResourceContainers(metadata, dublinCore.relation, dublinCore.creator)

                    if (ContainerType.of(rc.type()) == ContainerType.Help) {
                        if (relatedDublinCoreIds.isEmpty()) {
                            logger.error("Unmatched help for ${rc.manifest.dublinCore.identifier}")
                            throw ImportException(ImportResult.UNMATCHED_HELP)
                        }
                        relatedDublinCoreIds.forEach { relatedId ->
                            val ih = ImportHelper(metadata, relatedId)
                            ih.import(rcTree)
                        }
                    } else {
                        val ih = ImportHelper(metadata, null)
                        ih.import(rcTree)
                    }
                }
            }
            .toSingleDefault(ImportResult.SUCCESS)
            .onErrorReturn { e ->
                logger.error("Error in importResourceContainer for rc: $rc, language: $languageSlug", e)
                e.castOrFindImportException()?.result ?: ImportResult.LOAD_RC_ERROR
            }
            .doFinally { rc.close() }
            .subscribeOn(Schedulers.io())
    }

    /** The installed source (not a derived row) stored at [file], if any. */
    private fun sourceEntityAt(file: File): ResourceMetadataEntity? {
        val target = file.canonicalFile
        return resourceMetadataDao.fetchAll().firstOrNull {
            it.derivedFromFk == null && File(it.path).canonicalFile == target
        }
    }

    /**
     * Insert metadata, return metadata modified to include row ID.
     *
     * Other editions of the same source may already be installed; only the same edition (same
     * creator and [fingerprint]) is refused. The unique index on source editions backs this up.
     *
     * @throws [ImportException] if the same edition is already installed.
     */
    private fun insertMetadataOrThrow(
        metadata: ResourceMetadata,
        fingerprint: EditionFingerprint?
    ): ResourceMetadata {
        if (fingerprint != null) {
            val sameEdition = resourceMetadataDao
                .fetchSourceEditions(metadata.language.id, metadata.identifier)
                .firstOrNull { row ->
                    val stored = resourceMetadataDao.fetchEditionFingerprint(row.id)
                    row.creator == metadata.creator &&
                        stored?.structureFingerprint == fingerprint.structureFingerprint &&
                        stored.textFingerprint == fingerprint.textFingerprint
                }
            if (sameEdition != null) {
                logger.error("Error in inserting metadata, this edition is already installed: $sameEdition")
                throw ImportException(ImportResult.ALREADY_EXISTS)
            }
        }
        val entity = ResourceMetadataMapper().mapToEntity(metadata)
        val rowId = resourceMetadataDao.insert(entity)
        return metadata.copy(id = rowId)
    }

    private fun linkRelatedResourceContainers(
        newDublinCore: ResourceMetadata,
        relations: List<String>,
        creator: String
    ): List<Int> {
        val relatedIds = mutableListOf<Int>()
        relations.forEach { relation ->
            val (languageSlug, identifier) = relation.split('/')
            newestSourceEdition(languageSlug, identifier, creator)
                ?.let { relatedDublinCore ->
                    resourceMetadataDao.addLink(newDublinCore.id, relatedDublinCore.id)
                    relatedIds.add(relatedDublinCore.id)
                }
        }
        return relatedIds
    }

    /**
     * The newest installed edition (see EditionOrder) of [identifier] in [languageSlug], preferring
     * one by [creator] and falling back to any creator.
     */
    private fun newestSourceEdition(languageSlug: String, identifier: String, creator: String): ResourceMetadataEntity? {
        val languageEntity = languageDao.fetchBySlug(languageSlug) ?: return null
        val language = LanguageMapper().mapFromEntity(languageEntity)
        val editions = resourceMetadataDao.fetchSourceEditions(languageEntity.id, identifier)
        val candidates = editions.filter { it.creator == creator }.ifEmpty { editions }
        val newest = EditionOrder.newest(candidates.map { ResourceMetadataMapper().mapFromEntity(it, language) })
        return candidates.firstOrNull { it.id == newest?.id }
    }

    override fun removeResourceContainer(
        resourceContainer: ResourceContainer
    ): Single<DeleteResult> {
        return Single.fromCallable {
            var result = DeleteResult.SUCCESS

            database.transaction {
                val metadataEntity = sourceEntityAt(resourceContainer.file)

                val derivedRcExists = resourceMetadataDao.fetchAll().any {
                    it.derivedFromFk != null && it.derivedFromFk == metadataEntity?.id
                }

                when {
                    metadataEntity == null -> {
                        result = DeleteResult.NOT_FOUND
                        return@transaction
                    }

                    derivedRcExists -> {
                        result = DeleteResult.DEPENDENCY_EXISTS
                        return@transaction
                    }

                    else -> metadataEntity!!
                }

                // delete entities with foreign keys refer to rc first
                collectionDao.fetchAll()
                    .filter { it.dublinCoreFk == metadataEntity.id }
                    .forEach {
                        collectionDao.delete(it)
                    }

                resourceMetadataDao.delete(metadataEntity)
            }

            result
        }
            .doOnError { e ->
                logger.error("Error in removeResourceContainer.", e)
            }
            .doFinally { resourceContainer.close() }
            .subscribeOn(Schedulers.io())
    }

    override suspend fun importResourceContainerSuspend(
        rc: ResourceContainer,
        rcTree: OtterTree<CollectionOrContent>,
        languageSlug: String,
        fingerprint: EditionFingerprint?
    ): ImportResult = importResourceContainer(rc, rcTree, languageSlug, fingerprint).await()

    override suspend fun removeResourceContainerSuspend(
        resourceContainer: ResourceContainer
    ): DeleteResult = removeResourceContainer(resourceContainer).await()

    private fun findRootCollectionsForRc(dublinCoreId: Int): List<Collection> {
        return collectionRepository
            .getRootSources()
            .blockingGet()
            .filter { it.resourceContainer?.id == dublinCoreId }
    }

    inner class ImportHelper(
        private val metadata: ResourceMetadata,
        private val relatedBundleDublinCoreId: Int?
    ) {
        fun import(node: OtterTreeNode<CollectionOrContent>) {
            importCollection(null, node)

            relatedBundleDublinCoreId
                ?.let(::findRootCollectionsForRc)
                ?.map { it.id }
                ?.forEach(resourceRepository::calculateAndSetSubtreeHasResources)
        }

        /** Finds a collection from the database that matches the given collection on slug, label, and containerId. */
        private fun fetchCollectionFromDb(collection: Collection, containerId: Int): Collection? {
            val entity = collectionDao.fetch(
                slug = collection.slug,
                label = collection.labelKey,
                containerId = containerId
            )

            return entity?.let {
                CollectionMapper().mapFromEntity(it, collection.resourceContainer)
            }
        }

        /** Add collection to the database, return copy of collection that includes database row id. */
        private fun addCollection(collection: Collection, parent: Collection?): Collection {
            val entity = CollectionMapper().mapToEntity(collection).apply {
                parentFk = parent?.id
                dublinCoreFk = metadata.id
            }
            val insertedId = collectionDao.insert(entity)
            return collection.copy(id = insertedId)
        }

        private fun importCollection(parent: Collection?, node: OtterTreeNode<CollectionOrContent>): Collection? {
            val collection = (node.value as Collection).let { collection ->
                when (relatedBundleDublinCoreId) {
                    null -> addCollection(collection, parent)
                    else -> fetchCollectionFromDb(collection, relatedBundleDublinCoreId)
                    // TODO: If we don't find a corresponding collection, we continue on, setting collection = null.
                    // TODO: ... Eventually, contents will not be created if there is no parentId. This will happen for
                    // TODO: ... front matter until we have another solution.
                }
            }

            val children = (node as? OtterTree<CollectionOrContent>)?.children
            if (children != null) {
                if (collection != null) {
                    val contents = children.filter { it.value is Content }
                    importContent(collection, contents)
                }
                children
                    .filter { it.value is Collection }
                    .forEach {
                        importCollection(collection, it)
                    }
                if (collection != null) {
                    linkChapterResources(collection)
                    linkVerseResources(collection)
                }
            }

            return collection
        }

        private fun importContent(parent: Collection, nodes: List<OtterTreeNode<CollectionOrContent>>) {
            val entities = nodes
                .mapNotNull { it.value as? Content }
                .map { contentMapper.mapToEntity(it).apply { collectionFk = parent.id } }
            if (entities.isNotEmpty()) contentDao.insertNoReturn(*entities.toTypedArray())
        }

        private fun linkVerseResources(parentCollection: Collection) {
            resourceLinkDao.insertLinkableVerses(
                dublinCoreId = metadata.id,
                parentCollectionId = parentCollection.id,
                mainTypeIds = primaryContentTypes.map(contentTypeDao::fetchId),
                helpTypeIds = helpContentTypes.map(contentTypeDao::fetchId),
            )
        }

        private fun linkChapterResources(parentCollection: Collection) {
            resourceLinkDao.insertLinkableChapters(
                dublinCoreId = metadata.id,
                collectionId = parentCollection.id,
                helpTypeIds = helpContentTypes.map(contentTypeDao::fetchId),
            )
        }
    }
}
