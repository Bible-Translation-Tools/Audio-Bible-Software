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

import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.bibletranslationtools.otter.common.persistence.entities.MarkerEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceLinkEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.otter.common.persistence.entities.TakeEntity
import org.bibletranslationtools.otter.common.persistence.entities.TranslationEntity
import org.bibletranslationtools.otter.common.persistence.entities.WorkbookDescriptorEntity
import org.bibletranslationtools.otter.db.Collection_entity
import org.bibletranslationtools.otter.db.Content_entity
import org.bibletranslationtools.otter.db.Dublin_core_entity
import org.bibletranslationtools.otter.db.Language_entity
import org.bibletranslationtools.otter.db.Marker_entity
import org.bibletranslationtools.otter.db.Resource_link
import org.bibletranslationtools.otter.db.Take_entity
import org.bibletranslationtools.otter.db.Translation_entity
import org.bibletranslationtools.otter.db.Workbook_descriptor_entity

/**
 * Row → entity mappers, the SQLDelight counterpart of jOOQ's RecordMappers. The generated
 * `Xxx_entity` row classes already carry the right Kotlin types (Int/Boolean via the column
 * adapters), so these are straight field copies, with the same field-order the entity constructors
 * (and RecordMappers) use.
 *
 * A couple of columns are nullable in the schema but non-null on the entity — jOOQ read them the
 * same way and they are always written on insert: `language_entity.region` defaults to "" and
 * `translation_entity` rates default to 1.0 (the schema default the DAO relies on).
 */
internal fun Language_entity.toEntity() = LanguageEntity(
    id = id,
    slug = slug,
    name = name,
    anglicizedName = anglicized,
    direction = direction,
    gateway = gateway,
    region = region ?: "",
)

internal fun Collection_entity.toEntity() = CollectionEntity(
    id = id,
    parentFk = parent_fk,
    sourceFk = source_fk,
    label = label,
    title = title,
    slug = slug,
    sort = sort,
    dublinCoreFk = dublin_core_fk,
    modifiedTs = modified_ts,
)

internal fun Content_entity.toEntity() = ContentEntity(
    id = id,
    sort = sort,
    labelKey = label,
    start = start,
    end = v_end,
    collectionFk = collection_fk,
    selectedTakeFk = selected_take_fk,
    text = text,
    format = format,
    type_fk = type_fk,
    draftNumber = draft_number,
    bridged = bridged,
)

internal fun Take_entity.toEntity() = TakeEntity(
    id = id,
    contentFk = content_fk,
    filename = filename,
    filepath = path,
    number = number,
    createdTs = created_ts,
    deletedTs = deleted_ts,
    played = played,
    checkingFk = checking_fk,
    checksum = checksum,
)

internal fun Marker_entity.toEntity() = MarkerEntity(
    id = id,
    takeFk = take_fk,
    number = number,
    position = position,
    label = label,
)

internal fun Dublin_core_entity.toEntity() = ResourceMetadataEntity(
    id = id,
    conformsTo = conformsTo,
    creator = creator,
    description = description,
    format = format,
    identifier = identifier,
    issued = issued,
    languageFk = language_fk,
    modified = modified,
    publisher = publisher,
    subject = subject,
    type = type,
    title = title,
    version = version,
    license = license,
    path = path,
    derivedFromFk = derivedFrom_fk,
)

internal fun Resource_link.toEntity() = ResourceLinkEntity(
    id = id,
    resourceContentFk = resource_content_fk,
    contentFk = content_fk,
    collectionFk = collection_fk,
    dublinCoreFk = dublin_core_fk,
)

internal fun Translation_entity.toEntity() = TranslationEntity(
    id = id,
    sourceFk = source_fk,
    targetFk = target_fk,
    modifiedTs = modified_ts,
    sourceRate = source_rate ?: 1.0,
    targetRate = target_rate ?: 1.0,
)

internal fun Workbook_descriptor_entity.toEntity() = WorkbookDescriptorEntity(
    id = id,
    sourceFk = source_FK,
    targetFk = target_FK,
    typeFk = type_fk,
)
