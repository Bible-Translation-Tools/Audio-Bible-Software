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
package org.bibletranslationtools.otter.common.persistence.database.jooqcompat

import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.bibletranslationtools.otter.common.persistence.database.dao.CheckingStatusDao
import org.bibletranslationtools.otter.common.persistence.database.dao.CollectionDao
import org.bibletranslationtools.otter.common.persistence.database.dao.ContentDao
import org.bibletranslationtools.otter.common.persistence.database.dao.ContentTypeDao
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.database.dao.InstalledEntityDao
import org.bibletranslationtools.otter.common.persistence.database.dao.LanguageDao
import org.bibletranslationtools.otter.common.persistence.database.dao.MarkerDao
import org.bibletranslationtools.otter.common.persistence.database.dao.ResourceLinkDao
import org.bibletranslationtools.otter.common.persistence.database.dao.ResourceMetadataDao
import org.bibletranslationtools.otter.common.persistence.database.dao.SubtreeHasResourceDao
import org.bibletranslationtools.otter.common.persistence.database.dao.TakeDao
import org.bibletranslationtools.otter.common.persistence.database.dao.TranslationDao
import org.bibletranslationtools.otter.common.persistence.database.dao.VersificationDao
import org.bibletranslationtools.otter.common.persistence.database.dao.WorkbookDescriptorDao
import org.bibletranslationtools.otter.common.persistence.database.dao.WorkbookTypeDao
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.bibletranslationtools.otter.common.persistence.entities.MarkerEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceLinkEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.otter.common.persistence.entities.TakeEntity
import org.bibletranslationtools.otter.common.persistence.entities.TranslationEntity
import org.bibletranslationtools.otter.common.persistence.entities.WorkbookDescriptorEntity
import org.bibletranslationtools.otter.common.persistence.database.daos.CheckingStatusDao as JooqCheckingStatusDao
import org.bibletranslationtools.otter.common.persistence.database.daos.CollectionDao as JooqCollectionDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentDao as JooqContentDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentTypeDao as JooqContentTypeDao
import org.bibletranslationtools.otter.common.persistence.database.daos.InstalledEntityDao as JooqInstalledEntityDao
import org.bibletranslationtools.otter.common.persistence.database.daos.LanguageDao as JooqLanguageDao
import org.bibletranslationtools.otter.common.persistence.database.daos.MarkerDao as JooqMarkerDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ResourceLinkDao as JooqResourceLinkDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ResourceMetadataDao as JooqResourceMetadataDao
import org.bibletranslationtools.otter.common.persistence.database.daos.SubtreeHasResourceDao as JooqSubtreeHasResourceDao
import org.bibletranslationtools.otter.common.persistence.database.daos.TakeDao as JooqTakeDao
import org.bibletranslationtools.otter.common.persistence.database.daos.TranslationDao as JooqTranslationDao
import org.bibletranslationtools.otter.common.persistence.database.daos.VersificationDao as JooqVersificationDao
import org.bibletranslationtools.otter.common.persistence.database.daos.WorkbookDescriptorDao as JooqWorkbookDescriptorDao
import org.bibletranslationtools.otter.common.persistence.database.daos.WorkbookTypeDao as JooqWorkbookTypeDao

/**
 * The production jOOQ→[DaoProvider] adapter: presents the legacy jOOQ backend ([IAppDatabase] and its
 * `daos.*`) through the clean, jOOQ-free [DaoProvider] interfaces, so callers that resolve `DaoProvider`
 * from DI don't care which backend is active. Each adapter here is pure delegation to the existing jOOQ
 * DAO, calling it with its default `DSLContext`.
 *
 * Two roles: it is also what the shared characterization suite runs the jOOQ side of the differential
 * tests against (via `JooqBackend` in `desktopTest`), so it doubles as the oracle adapter. Some methods
 * throw [UnsupportedOperationException] — the DAO-level equivalents of the three repositories
 * (Collection/Resource/ResourceContainer) that were rewritten onto SQLDelight-only queries in Phase 4;
 * restoring jOOQ as a live production backend for those would require pulling their pre-Phase-4 jOOQ
 * implementations back out of git history. Removed entirely once jOOQ itself is deleted.
 */
class JooqDaoProvider(private val database: IAppDatabase) : DaoProvider {
    override val languageDao = LanguageDaoAdapter(database.languageDao)
    override val resourceMetadataDao = ResourceMetadataDaoAdapter(database.resourceMetadataDao)
    override val collectionDao = CollectionDaoAdapter(database.collectionDao)
    override val contentTypeDao = ContentTypeDaoAdapter(database.contentTypeDao)
    override val contentDao = ContentDaoAdapter(database.contentDao)
    override val resourceLinkDao = ResourceLinkDaoAdapter(database.resourceLinkDao)
    override val subtreeHasResourceDao = SubtreeHasResourceDaoAdapter(database.subtreeHasResourceDao)
    override val takeDao = TakeDaoAdapter(database.takeDao)
    override val markerDao = MarkerDaoAdapter(database.markerDao)
    override val installedEntityDao = InstalledEntityDaoAdapter(database.installedEntityDao)
    override val translationDao = TranslationDaoAdapter(database.translationDao)
    override val versificationDao = VersificationDaoAdapter(database.versificationDao)
    override val workbookTypeDao = WorkbookTypeDaoAdapter(database.workbookTypeDao)
    override val workbookDescriptorDao = WorkbookDescriptorDaoAdapter(database.workbookDescriptorDao)
    override val checkingStatusDao = CheckingStatusDaoAdapter(database.checkingStatusDao)

    override fun transaction(block: () -> Unit) = database.transaction { block() }
    override fun <T> transactionResult(block: () -> T): T = database.transactionResult { block() }
}

class LanguageDaoAdapter(private val d: JooqLanguageDao) : LanguageDao {
    override fun fetchGateway() = d.fetchGateway()
    override fun fetchTargets() = d.fetchTargets()
    override fun fetchBySlug(slug: String) = d.fetchBySlug(slug)
    override fun insert(entity: LanguageEntity) = d.insert(entity)
    override fun insertAll(entities: List<LanguageEntity>) = d.insertAll(entities)
    override fun updateRegions(entities: List<LanguageEntity>) = d.updateRegions(entities)
    override fun fetchById(id: Int) = d.fetchById(id)
    override fun fetchByIds(ids: List<Int>) = d.fetchByIds(ids)
    override fun fetchAll() = d.fetchAll()
    override fun update(entity: LanguageEntity) = d.update(entity)
    override fun updateAll(entities: List<LanguageEntity>) = d.updateAll(entities)
    override fun delete(entity: LanguageEntity) = d.delete(entity)
}

class ResourceMetadataDaoAdapter(private val d: JooqResourceMetadataDao) : ResourceMetadataDao {
    override fun exists(languageId: Int, identifier: String, version: String, creator: String) =
        d.exists(languageId, identifier, version, creator)
    override fun fetch(languageId: Int, identifier: String, version: String, creator: String) =
        d.fetch(languageId, identifier, version, creator)
    override fun fetchLinks(entityId: Int) = d.fetchLinks(entityId)
    override fun addLink(entity1Id: Int, entity2Id: Int) = d.addLink(entity1Id, entity2Id)
    override fun removeLink(entity1Id: Int, entity2Id: Int) = d.removeLink(entity1Id, entity2Id)
    override fun insert(entity: ResourceMetadataEntity) = d.insert(entity)
    override fun fetchById(id: Int) = d.fetchById(id)
    override fun fetchByIds(ids: List<Int>) = d.fetchByIds(ids)
    override fun fetchLatestVersion(
        languageSlug: String,
        identifier: String,
        creator: String,
        derivedFromFk: Int?,
        relaxCreatorIfNoMatch: Boolean,
    ) = d.fetchLatestVersion(languageSlug, identifier, creator, derivedFromFk, relaxCreatorIfNoMatch)
    override fun fetchLatestVersion(languageSlug: String, identifier: String) =
        d.fetchLatestVersion(languageSlug, identifier)
    override fun fetchAll() = d.fetchAll()
    override fun update(entity: ResourceMetadataEntity) = d.update(entity)
    override fun delete(entity: ResourceMetadataEntity) = d.delete(entity)
    override fun resourceMetadataByContent(contentId: Int): List<ResourceMetadataEntity> =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; resourceMetadataByContent runs on SQLDelight"
        )
    override fun resourceMetadataByCollection(collectionId: Int): List<ResourceMetadataEntity> =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; resourceMetadataByCollection runs on SQLDelight"
        )
    override fun subtreeResourceMetadata(collectionId: Int): List<ResourceMetadataEntity> =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; subtreeResourceMetadata runs on SQLDelight"
        )
}

class CollectionDaoAdapter(private val d: JooqCollectionDao) : CollectionDao {
    override fun fetchChildren(entity: CollectionEntity) = d.fetchChildren(entity)
    override fun fetchSource(entity: CollectionEntity) = d.fetchSource(entity)
    override fun fetch(slug: String, containerId: Int, label: String) = d.fetch(slug, containerId, label)
    override fun insert(entity: CollectionEntity) = d.insert(entity)
    override fun fetchById(id: Int) = d.fetchById(id)
    override fun fetchAll() = d.fetchAll()
    override fun fetchByIds(ids: List<Int>) = d.fetchByIds(ids)
    override fun fetchByLabel(label: String) = d.fetchByLabel(label)
    override fun update(entity: CollectionEntity) = d.update(entity)
    override fun delete(entity: CollectionEntity) = d.delete(entity)
    override fun collectionsWithoutTakes(projectEntity: CollectionEntity) = d.collectionsWithoutTakes(projectEntity)
    override fun copyChapters(sourceId: Int, projectId: Int, metadataId: Int): Unit =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; copyChapters runs on SQLDelight"
        )
    override fun selectSourceLinkedRc2Fks(projectId: Int): List<Int> =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; selectSourceLinkedRc2Fks runs on SQLDelight"
        )
}

class ContentTypeDaoAdapter(private val d: JooqContentTypeDao) : ContentTypeDao {
    override fun fetchId(contentType: ContentType) = d.fetchId(contentType)
    override fun fetchForId(databaseId: Int) = d.fetchForId(databaseId)
}

class ContentDaoAdapter(private val d: JooqContentDao) : ContentDao {
    override fun fetchByCollectionId(collectionId: Int) = d.fetchByCollectionId(collectionId)
    override fun fetchByCollectionIdAndStart(collectionId: Int, start: Int, types: Collection<ContentType>) =
        d.fetchByCollectionIdAndStart(collectionId, start, types)
    override fun fetchByCollectionIdAndType(collectionId: Int, type: ContentType) =
        d.fetchByCollectionIdAndType(collectionId, type)
    override fun fetchSources(entity: ContentEntity) = d.fetchSources(entity)
    override fun updateSources(entity: ContentEntity, sources: List<ContentEntity>) = d.updateSources(entity, sources)
    override fun insert(entity: ContentEntity) = d.insert(entity)
    override fun insertNoReturn(vararg entities: ContentEntity) = d.insertNoReturn(*entities)
    override fun fetchById(id: Int) = d.fetchById(id)
    override fun fetchAll() = d.fetchAll()
    override fun updateAll(entities: List<ContentEntity>) = d.updateAll(entities)
    override fun update(entity: ContentEntity) = d.update(entity)
    override fun delete(entity: ContentEntity) = d.delete(entity)
    override fun deleteForCollection(chapterCollection: CollectionEntity, contentTypeId: Int?) =
        d.deleteForCollection(chapterCollection, contentTypeId)
    override fun linkDerivative(contentId: Int, sourceContentId: Int) = d.linkDerivative(contentId, sourceContentId)
    override fun copyContent(sourceId: Int, metadataId: Int): Unit =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; copyContent runs on SQLDelight"
        )
    override fun copyMetaContent(sourceId: Int, metadataId: Int): Unit =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; copyMetaContent runs on SQLDelight"
        )
    override fun linkDerivativeContent(sourceId: Int, projectId: Int): Unit =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; linkDerivativeContent runs on SQLDelight"
        )
    override fun resourcesForContent(contentId: Int, dublinCoreId: Int): List<ContentEntity> =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; resourcesForContent runs on SQLDelight"
        )
    override fun resourcesForCollection(collectionId: Int, dublinCoreId: Int): List<ContentEntity> =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; resourcesForCollection runs on SQLDelight"
        )
}

class ResourceLinkDaoAdapter(private val d: JooqResourceLinkDao) : ResourceLinkDao {
    override fun fetchByContentId(id: Int) = d.fetchByContentId(id)
    override fun fetchByCollectionId(id: Int) = d.fetchByCollectionId(id)
    override fun insert(entity: ResourceLinkEntity) = d.insert(entity)
    override fun insertNoReturn(vararg entities: ResourceLinkEntity) = d.insertNoReturn(*entities)
    override fun fetchById(id: Int) = d.fetchById(id)
    override fun fetchAll() = d.fetchAll()
    override fun update(entity: ResourceLinkEntity) = d.update(entity)
    override fun delete(entity: ResourceLinkEntity) = d.delete(entity)
    override fun copyResourceLinks(
        sourceMetadataId: Int,
        derivedMetadataId: Int,
        projectId: Int,
        projectDublinCoreFk: Int,
    ): Unit =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; copyResourceLinks runs on SQLDelight"
        )
    override fun insertLinkableVerses(
        dublinCoreId: Int,
        parentCollectionId: Int,
        mainTypeIds: Collection<Int>,
        helpTypeIds: Collection<Int>,
    ): Unit =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; insertLinkableVerses runs on SQLDelight"
        )
    override fun insertLinkableChapters(dublinCoreId: Int, collectionId: Int, helpTypeIds: Collection<Int>): Unit =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; insertLinkableChapters runs on SQLDelight"
        )
    override fun contentResourceMetadataFksByCollection(collectionId: Int): List<Int> =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; contentResourceMetadataFksByCollection runs on SQLDelight"
        )
}

class SubtreeHasResourceDaoAdapter(private val d: JooqSubtreeHasResourceDao) : SubtreeHasResourceDao {
    override fun insert(collectionId: Int, dublinCoreId: Int) = d.insert(collectionId, dublinCoreId)
    override fun insert(collectionIdsToDublinCoreIds: Sequence<Pair<Int, Int>>) = d.insert(collectionIdsToDublinCoreIds)
    override fun fetchDublinCoreIdsByCollectionId(id: Int) = d.fetchDublinCoreIdsByCollectionId(id)
}

class TakeDaoAdapter(private val d: JooqTakeDao) : TakeDao {
    override fun fetchByContentId(id: Int, includeDeleted: Boolean) = d.fetchByContentId(id, includeDeleted)
    override fun insert(entity: TakeEntity) = d.insert(entity)
    override fun fetchById(id: Int) = d.fetchById(id)
    override fun fetchAll() = d.fetchAll()
    override fun update(entity: TakeEntity) = d.update(entity)
    override fun delete(entity: TakeEntity) = d.delete(entity)
    override fun fetchSoftDeletedTakes(collectionEntity: CollectionEntity) = d.fetchSoftDeletedTakes(collectionEntity)
    override fun fetchSoftDeletedTakes() = d.fetchSoftDeletedTakes()
    override fun fetchByCollectionId(id: Int, includeDeleted: Boolean) = d.fetchByCollectionId(id, includeDeleted)
    override fun deleteResourceTakesForProject(projectId: Int, projectSlug: String): Unit =
        throw UnsupportedOperationException(
            "jOOQ backend is characterization-only; deleteResourceTakesForProject runs on SQLDelight"
        )
}

class MarkerDaoAdapter(private val d: JooqMarkerDao) : MarkerDao {
    override fun fetchByTakeId(id: Int) = d.fetchByTakeId(id)
    override fun insert(entity: MarkerEntity) = d.insert(entity)
    override fun fetchById(id: Int) = d.fetchById(id)
    override fun fetchAll() = d.fetchAll()
    override fun update(entity: MarkerEntity) = d.update(entity)
    override fun delete(entity: MarkerEntity) = d.delete(entity)
}

class InstalledEntityDaoAdapter(private val d: JooqInstalledEntityDao) : InstalledEntityDao {
    override fun upsert(entity: Installable) = d.upsert(entity)
    override fun fetchVersion(entity: Installable) = d.fetchVersion(entity)
}

class TranslationDaoAdapter(private val d: JooqTranslationDao) : TranslationDao {
    override fun fetch(sourceId: Int, targetId: Int) = d.fetch(sourceId, targetId)
    override fun fetchById(id: Int) = d.fetchById(id)
    override fun fetchAll() = d.fetchAll()
    override fun insert(entity: TranslationEntity) = d.insert(entity)
    override fun update(entity: TranslationEntity) = d.update(entity)
    override fun delete(entity: TranslationEntity) = d.delete(entity)
}

class VersificationDaoAdapter(private val d: JooqVersificationDao) : VersificationDao {
    override fun fetchVersificationFile(slug: String) = d.fetchVersificationFile(slug)
    override fun insert(slug: String, path: String) = d.insert(slug, path)
    override fun update(slug: String, path: String) = d.update(slug, path)
    override fun upsert(slug: String, path: String) = d.upsert(slug, path)
}

class WorkbookTypeDaoAdapter(private val d: JooqWorkbookTypeDao) : WorkbookTypeDao {
    override fun fetchId(mode: ProjectMode) = d.fetchId(mode)
    override fun fetchById(databaseId: Int) = d.fetchById(databaseId)
}

class WorkbookDescriptorDaoAdapter(private val d: JooqWorkbookDescriptorDao) : WorkbookDescriptorDao {
    override fun fetch(sourceId: Int, targetId: Int, typeId: Int) = d.fetch(sourceId, targetId, typeId)
    override fun fetchById(id: Int) = d.fetchById(id)
    override fun fetchAll() = d.fetchAll()
    override fun insert(entity: WorkbookDescriptorEntity) = d.insert(entity)
    override fun update(entity: WorkbookDescriptorEntity) = d.update(entity)
    override fun delete(entity: WorkbookDescriptorEntity) = d.delete(entity)
}

class CheckingStatusDaoAdapter(private val d: JooqCheckingStatusDao) : CheckingStatusDao {
    override fun fetchId(mode: CheckingStatus) = d.fetchId(mode)
    override fun fetchById(databaseId: Int) = d.fetchById(databaseId)
}
