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

import app.cash.sqldelight.adapter.primitive.IntColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import org.bibletranslationtools.otter.common.persistence.database.DATABASE_INSTALLABLE_NAME
import org.bibletranslationtools.otter.common.persistence.database.SCHEMA_VERSION
import org.bibletranslationtools.otter.db.Checking_status
import org.bibletranslationtools.otter.db.Collection_entity
import org.bibletranslationtools.otter.db.Content_derivative
import org.bibletranslationtools.otter.db.Content_entity
import org.bibletranslationtools.otter.db.Content_type
import org.bibletranslationtools.otter.db.Dublin_core_entity
import org.bibletranslationtools.otter.db.Installed_entity
import org.bibletranslationtools.otter.db.Language_entity
import org.bibletranslationtools.otter.db.Marker_entity
import org.bibletranslationtools.otter.db.OtterDatabase
import org.bibletranslationtools.otter.db.Rc_link_entity
import org.bibletranslationtools.otter.db.Resource_link
import org.bibletranslationtools.otter.db.Subtree_has_resource
import org.bibletranslationtools.otter.db.Take_entity
import org.bibletranslationtools.otter.db.Translation_entity
import org.bibletranslationtools.otter.db.Workbook_descriptor_entity
import org.bibletranslationtools.otter.db.Workbook_type

/**
 * Wires the generated [OtterDatabase] to a driver. Every `INTEGER AS kotlin.Int` column is served by
 * the shared [IntColumnAdapter] (order within an Adapter is irrelevant — all columns take the same
 * adapter); `content_entity.bridged` uses SQLDelight's built-in Boolean encoding, so it needs none.
 */
internal fun buildOtterDatabase(driver: SqlDriver): OtterDatabase {
    val i = IntColumnAdapter
    return OtterDatabase(
        driver = driver,
        checking_statusAdapter = Checking_status.Adapter(i),
        collection_entityAdapter = Collection_entity.Adapter(i, i, i, i, i),
        content_derivativeAdapter = Content_derivative.Adapter(i, i, i),
        content_entityAdapter = Content_entity.Adapter(i, i, i, i, i, i, i, i),
        content_typeAdapter = Content_type.Adapter(i),
        dublin_core_entityAdapter = Dublin_core_entity.Adapter(i, i, i),
        installed_entityAdapter = Installed_entity.Adapter(i),
        language_entityAdapter = Language_entity.Adapter(i, i),
        marker_entityAdapter = Marker_entity.Adapter(i, i, i, i),
        rc_link_entityAdapter = Rc_link_entity.Adapter(i, i),
        resource_linkAdapter = Resource_link.Adapter(i, i, i, i, i),
        subtree_has_resourceAdapter = Subtree_has_resource.Adapter(i, i),
        take_entityAdapter = Take_entity.Adapter(i, i, i, i, i),
        translation_entityAdapter = Translation_entity.Adapter(i, i, i),
        workbook_descriptor_entityAdapter = Workbook_descriptor_entity.Adapter(i, i, i, i),
        workbook_typeAdapter = Workbook_type.Adapter(i),
    )
}

/**
 * Bootstraps a fresh database: creates the schema and stamps the installed "DATABASE" version, the
 * same end state jOOQ's `AppDatabase.setup()` produces on a new file. Enum tables (content_type,
 * checking_status, workbook_type) are left empty here and lazily seeded by their DAOs — matching jOOQ.
 */
internal fun createFreshOtterDatabase(driver: SqlDriver): OtterDatabase {
    OtterDatabase.Schema.create(driver)
    val database = buildOtterDatabase(driver)
    database.installedEntityQueries.upsertUpdate(version = SCHEMA_VERSION, name = DATABASE_INSTALLABLE_NAME)
    database.installedEntityQueries.upsertInsertIfUnchanged(name = DATABASE_INSTALLABLE_NAME, version = SCHEMA_VERSION)
    return database
}
