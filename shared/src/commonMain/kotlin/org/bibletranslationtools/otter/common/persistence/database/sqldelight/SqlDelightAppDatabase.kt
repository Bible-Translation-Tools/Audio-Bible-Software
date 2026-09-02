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

import app.cash.sqldelight.db.SqlDriver
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
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
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * The SQLDelight-backed [DaoProvider]. Wires the SQLDelight DAOs over one generated [OtterDatabase].
 * Enum DAOs are declared before [contentDao], which depends on [contentTypeDao].
 */
class SqlDelightAppDatabase(private val database: OtterDatabase) : DaoProvider {
    override val languageDao: LanguageDao = SqlDelightLanguageDao(database)
    override val checkingStatusDao: CheckingStatusDao = SqlDelightCheckingStatusDao(database)
    override val contentTypeDao: ContentTypeDao = SqlDelightContentTypeDao(database)
    override val workbookTypeDao: WorkbookTypeDao = SqlDelightWorkbookTypeDao(database)
    override val resourceMetadataDao: ResourceMetadataDao = SqlDelightResourceMetadataDao(database)
    override val collectionDao: CollectionDao = SqlDelightCollectionDao(database)
    override val contentDao: ContentDao = SqlDelightContentDao(database, contentTypeDao)
    override val resourceLinkDao: ResourceLinkDao = SqlDelightResourceLinkDao(database)
    override val subtreeHasResourceDao: SubtreeHasResourceDao = SqlDelightSubtreeHasResourceDao(database)
    override val takeDao: TakeDao = SqlDelightTakeDao(database)
    override val markerDao: MarkerDao = SqlDelightMarkerDao(database)
    override val installedEntityDao: InstalledEntityDao = SqlDelightInstalledEntityDao(database)
    override val translationDao: TranslationDao = SqlDelightTranslationDao(database)
    override val versificationDao: VersificationDao = SqlDelightVersificationDao(database)
    override val workbookDescriptorDao: WorkbookDescriptorDao = SqlDelightWorkbookDescriptorDao(database)

    override fun transaction(block: () -> Unit) = database.transaction { block() }
    override fun <T> transactionResult(block: () -> T): T = database.transactionWithResult { block() }

    companion object {
        /** Build over a driver whose database is freshly created (schema + installed version stamped). */
        fun createFresh(driver: SqlDriver): SqlDelightAppDatabase =
            SqlDelightAppDatabase(createFreshOtterDatabase(driver))

        /**
         * Open-or-migrate entry point, mirroring jOOQ `AppDatabase`'s init decision: a brand-new
         * (or 0-byte) file gets [createFresh]'s schema-create + version stamp, while an existing
         * file is run through [SqlDelightDatabaseMigrator] for the v0->14 upgrade path before the
         * DAOs are wired up over it.
         */
        fun open(
            driver: SqlDriver,
            isNewDatabase: Boolean,
            directoryProvider: ITempFileProvider
        ): SqlDelightAppDatabase {
            if (isNewDatabase) {
                return createFresh(driver)
            }
            SqlDelightDatabaseMigrator(directoryProvider).migrate(driver)
            return SqlDelightAppDatabase(buildOtterDatabase(driver))
        }
    }
}
