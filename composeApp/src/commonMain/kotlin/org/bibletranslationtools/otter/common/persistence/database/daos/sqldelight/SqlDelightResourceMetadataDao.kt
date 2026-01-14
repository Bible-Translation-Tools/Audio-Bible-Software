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
package org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight

import org.bibletranslationtools.otter.common.persistence.database.AppDatabase
import org.bibletranslationtools.otter.common.persistence.database.Dublin_core_entity
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.ResourceMetadataEntity
import kotlin.math.min
import kotlin.math.max

class SqlDelightResourceMetadataDao(
    private val db: AppDatabase
) {
    private val queries = db.appDatabaseQueries

    fun fetchLinks(entityId: Int): List<ResourceMetadataEntity> {
        val links = queries.fetchLinks(entityId.toLong(), entityId.toLong()).executeAsList()
        val linkIds = links.map { 
            if (it.rc1_fk.toInt() == entityId) it.rc2_fk.toInt() else it.rc1_fk.toInt()
        }
        return linkIds.mapNotNull { fetchById(it) }
    }

    fun addLink(entity1Id: Int, entity2Id: Int) {
        queries.addLink(
            rc1_fk = min(entity1Id, entity2Id).toLong(),
            rc2_fk = max(entity1Id, entity2Id).toLong()
        )
    }

    fun removeLink(entity1Id: Int, entity2Id: Int) {
        queries.removeLink(
            rc1_fk = min(entity1Id, entity2Id).toLong(),
            rc2_fk = max(entity1Id, entity2Id).toLong()
        )
    }

    fun insert(entity: ResourceMetadataEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")

        queries.insertDublinCore(
            conformsTo = entity.conformsTo,
            creator = entity.creator,
            description = entity.description,
            format = entity.format,
            identifier = entity.identifier,
            issued = entity.issued,
            language_fk = entity.languageFk.toLong(),
            modified = entity.modified,
            publisher = entity.publisher,
            subject = entity.subject,
            type = entity.type,
            title = entity.title,
            version = entity.version,
            license = entity.license,
            path = entity.path,
            derivedFrom_fk = entity.derivedFromFk?.toLong()
        )

        return queries.lastInsertId().executeAsOne().toInt()
    }

    fun fetchById(id: Int): ResourceMetadataEntity? {
        return queries.fetchDublinCoreById(id.toLong()).executeAsOneOrNull()?.toResourceMetadataEntity()
    }

    fun fetchLatestVersion(
        languageSlug: String,
        identifier: String,
        creator: String,
        derivedFromFk: Int?,
        relaxCreatorIfNoMatch: Boolean = true
    ): ResourceMetadataEntity? {
        val result = if (derivedFromFk != null) {
            queries.fetchLatestVersion(languageSlug, identifier, creator, derivedFromFk.toLong()).executeAsOneOrNull()
        } else {
            queries.fetchLatestVersionNoDerived(languageSlug, identifier, creator).executeAsOneOrNull()
        }

        return result?.toResourceMetadataEntity() ?: if (relaxCreatorIfNoMatch) {
            if (derivedFromFk != null) {
                queries.fetchLatestVersion(languageSlug, identifier, null, derivedFromFk.toLong()).executeAsOneOrNull()
            } else {
                queries.fetchLatestVersionNoDerived(languageSlug, identifier, null).executeAsOneOrNull()
            }?.toResourceMetadataEntity()
        } else null
    }

    fun fetchLatestVersion(
        languageSlug: String,
        identifier: String
    ): ResourceMetadataEntity? {
        // We can reuse the same query but with null creator and null derivedFromFk
        return queries.fetchLatestVersionNoDerived(languageSlug, identifier, null).executeAsOneOrNull()?.toResourceMetadataEntity()
    }

    fun fetchAll(): List<ResourceMetadataEntity> {
        return queries.fetchAllDublinCore().executeAsList().map { it.toResourceMetadataEntity() }
    }

    fun update(entity: ResourceMetadataEntity) {
        queries.updateDublinCore(
            conformsTo = entity.conformsTo,
            creator = entity.creator,
            description = entity.description,
            format = entity.format,
            identifier = entity.identifier,
            issued = entity.issued,
            language_fk = entity.languageFk.toLong(),
            modified = entity.modified,
            publisher = entity.publisher,
            subject = entity.subject,
            type = entity.type,
            title = entity.title,
            version = entity.version,
            license = entity.license,
            path = entity.path,
            derivedFrom_fk = entity.derivedFromFk?.toLong(),
            id = entity.id.toLong()
        )
    }

    fun delete(entity: ResourceMetadataEntity) {
        queries.deleteDublinCore(entity.id.toLong())
    }
}

fun Dublin_core_entity.toResourceMetadataEntity(): ResourceMetadataEntity {
    return ResourceMetadataEntity(
        id = id.toInt(),
        conformsTo = conformsTo,
        creator = creator,
        description = description,
        format = format,
        identifier = identifier,
        issued = issued,
        languageFk = language_fk.toInt(),
        modified = modified,
        publisher = publisher,
        subject = subject,
        type = type,
        title = title,
        version = version,
        license = license,
        path = path,
        derivedFromFk = derivedFrom_fk?.toInt()
    )
}
