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
package org.bibletranslationtools.otter.common.persistence.database.sqldelight

import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.bibletranslationtools.otter.common.persistence.database.dao.ResourceMetadataDao
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [ResourceMetadataDao]. Behavior mirrors the jOOQ ResourceMetadataDao, including
 * the `insert → SELECT max(id)` id retrieval, min/max ordering of link foreign keys, and the
 * `fetchLatestVersion` retry that relaxes the creator filter when no exact match is found.
 */
internal class SqlDelightResourceMetadataDao(private val db: OtterDatabase) : ResourceMetadataDao {
    private val queries = db.dublinCoreQueries

    override fun exists(languageId: Int, identifier: String, version: String, creator: String): Boolean =
        queries.exists(languageId, identifier, version, creator).executeAsOne() > 0

    override fun fetch(
        languageId: Int,
        identifier: String,
        version: String,
        creator: String,
    ): ResourceMetadataEntity? =
        queries.fetch(languageId, identifier, version, creator).executeAsOneOrNull()?.toEntity()

    override fun fetchLinks(entityId: Int): List<ResourceMetadataEntity> =
        queries.fetchLinks(entityId).executeAsList().map { it.toEntity() }

    override fun addLink(entity1Id: Int, entity2Id: Int) {
        queries.addLink(low = minOf(entity1Id, entity2Id), high = maxOf(entity1Id, entity2Id))
    }

    override fun removeLink(entity1Id: Int, entity2Id: Int) {
        queries.removeLink(low = minOf(entity1Id, entity2Id), high = maxOf(entity1Id, entity2Id))
    }

    override fun insert(entity: ResourceMetadataEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")
        return db.transactionWithResult {
            queries.insert(
                conformsTo = entity.conformsTo,
                creator = entity.creator,
                description = entity.description,
                format = entity.format,
                identifier = entity.identifier,
                issued = entity.issued,
                languageFk = entity.languageFk,
                modified = entity.modified,
                publisher = entity.publisher,
                subject = entity.subject,
                type = entity.type,
                title = entity.title,
                version = entity.version,
                license = entity.license,
                path = entity.path,
                derivedFromFk = entity.derivedFromFk,
            )
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    override fun fetchById(id: Int): ResourceMetadataEntity? =
        queries.fetchById(id).executeAsOneOrNull()?.toEntity()

    override fun fetchByIds(ids: List<Int>): List<ResourceMetadataEntity> {
        if (ids.isEmpty()) return emptyList()
        return queries.fetchByIds(ids).executeAsList().map { it.toEntity() }
    }

    override fun fetchLatestVersion(
        languageSlug: String,
        identifier: String,
        creator: String,
        derivedFromFk: Int?,
        relaxCreatorIfNoMatch: Boolean,
    ): ResourceMetadataEntity? {
        fun flv(creatorArg: String?) =
            queries.fetchLatestVersion(languageSlug, identifier, creatorArg, derivedFromFk)
                .executeAsOneOrNull()
                ?.toEntity()

        return flv(creator)
            ?: if (relaxCreatorIfNoMatch) flv(null) else null
    }

    override fun fetchLatestVersion(languageSlug: String, identifier: String): ResourceMetadataEntity? =
        queries.fetchLatestVersionByLanguageAndIdentifier(languageSlug, identifier)
            .executeAsOneOrNull()
            ?.toEntity()

    override fun fetchAll(): List<ResourceMetadataEntity> =
        queries.fetchAll().executeAsList().map { it.toEntity() }

    override fun update(entity: ResourceMetadataEntity) {
        queries.update(
            conformsTo = entity.conformsTo,
            creator = entity.creator,
            description = entity.description,
            format = entity.format,
            identifier = entity.identifier,
            issued = entity.issued,
            languageFk = entity.languageFk,
            modified = entity.modified,
            publisher = entity.publisher,
            subject = entity.subject,
            type = entity.type,
            title = entity.title,
            version = entity.version,
            license = entity.license,
            path = entity.path,
            derivedFromFk = entity.derivedFromFk,
            id = entity.id,
        )
    }

    override fun delete(entity: ResourceMetadataEntity) {
        queries.delete(entity.id)
    }

    override fun resourceMetadataByContent(contentId: Int): List<ResourceMetadataEntity> =
        queries.resourceMetadataByContent(contentId).executeAsList().map { it.toEntity() }

    override fun resourceMetadataByCollection(collectionId: Int): List<ResourceMetadataEntity> =
        queries.resourceMetadataByCollection(collectionId).executeAsList().map { it.toEntity() }

    override fun subtreeResourceMetadata(collectionId: Int): List<ResourceMetadataEntity> =
        queries.subtreeResourceMetadata(collectionId).executeAsList().map { it.toEntity() }
}
