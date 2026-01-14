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
package org.bibletranslationtools.otter.common.persistence.database

import app.cash.sqldelight.db.SqlDriver
import org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight.*

class SqlDelightAppDatabase(driver: SqlDriver) {
    val database = AppDatabase(driver)
    
    val languageDao = SqlDelightLanguageDao(database)
    val resourceMetadataDao = SqlDelightResourceMetadataDao(database)
    val collectionDao = SqlDelightCollectionDao(database)
    val contentDao = SqlDelightContentDao(database)
    val takeDao = SqlDelightTakeDao(database)
    val versificationDao = SqlDelightVersificationDao(database)
    val translationDao = SqlDelightTranslationDao(database)
    val workbookDescriptorDao = SqlDelightWorkbookDescriptorDao(database)
    val installedEntityDao = SqlDelightInstalledEntityDao(database)
    val markerDao = SqlDelightMarkerDao(database)
    val resourceLinkDao = SqlDelightResourceLinkDao(database)
    val subtreeHasResourceDao = SqlDelightSubtreeHasResourceDao(database)
    
    // Enum-based DAOs
    val contentTypeDao = SqlDelightContentTypeDao(database)
    val checkingStatusDao = SqlDelightCheckingStatusDao(database)
    val workbookTypeDao = SqlDelightWorkbookTypeDao(database)
}
