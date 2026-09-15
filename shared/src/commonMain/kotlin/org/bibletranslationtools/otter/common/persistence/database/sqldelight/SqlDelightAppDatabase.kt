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

import app.cash.sqldelight.db.QueryResult
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
 * Enum DAOs are declared before [contentDao], which depends on [contentTypeDao]. Holds the raw
 * [driver] as well as the generated [database] so [contentDao]'s chunked bulk insert and
 * [withBulkLoad]'s PRAGMA/index management can issue statements directly against it.
 */
class SqlDelightAppDatabase(
    private val driver: SqlDriver,
    private val database: OtterDatabase
) : DaoProvider {
    override val languageDao: LanguageDao = SqlDelightLanguageDao(database, driver)
    override val checkingStatusDao: CheckingStatusDao = SqlDelightCheckingStatusDao(database)
    override val contentTypeDao: ContentTypeDao = SqlDelightContentTypeDao(database)
    override val workbookTypeDao: WorkbookTypeDao = SqlDelightWorkbookTypeDao(database)
    override val resourceMetadataDao: ResourceMetadataDao = SqlDelightResourceMetadataDao(database)
    override val collectionDao: CollectionDao = SqlDelightCollectionDao(database)
    override val contentDao: ContentDao = SqlDelightContentDao(database, driver, contentTypeDao)
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

    /**
     * Self-gates on "content table empty" so this only ever fires around the first-install seed:
     * on every later startup `content_entity` already has rows and [block] just runs plainly,
     * without rebuilding the index or flipping PRAGMAs on every launch.
     */
    override fun <T> withBulkLoad(block: () -> T): T {
        if (!contentTableEmpty()) return block()

        // PRAGMAs must be set OUTSIDE a transaction (the seeders open their own).
        // Crash-safe but fast: WAL (persists; a better steady-state journal anyway) + synchronous=NORMAL
        // stay durable across a power loss/kill mid-seed — unlike synchronous=OFF, which could leave the
        // fresh DB corrupt. foreign_keys are off only for the ordered, parent-first bulk insert, then
        // re-enabled and asserted clean below.
        //
        // We deliberately do NOT drop idx_content_entity_collection_start here. The importer interleaves
        // index-dependent self-join queries with its inserts — linkVerseResources/linkChapterResources run
        // an INSERT…SELECT self-join over content_entity on (collection_fk, start, type_fk) for EVERY
        // collection — so dropping the index turns those into full scans and makes the whole seed slower,
        // not faster. The per-insert index maintenance is far cheaper than that.
        //
        // journal_mode is the one PRAGMA that returns a row even when SETTING it, so it must go through
        // executeQuery: Android's framework driver rejects execute() (executeForChangedRowCount) on any
        // row-returning statement ("Queries can be performed using query or rawQuery only"). The
        // returned mode row is consumed and ignored; the switch to WAL is the side effect we want.
        driver.executeQuery(null, "PRAGMA journal_mode=WAL;", { cursor -> cursor.next(); QueryResult.Value(Unit) }, 0)
        driver.execute(null, "PRAGMA synchronous=NORMAL;", 0)
        driver.execute(null, "PRAGMA foreign_keys=OFF;", 0)
        try {
            return block()
        } finally {
            driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
            checkForeignKeys()
        }
    }

    private fun contentTableEmpty(): Boolean {
        val hasContent = driver.executeQuery(
            null,
            "SELECT EXISTS(SELECT 1 FROM content_entity);",
            { cursor -> QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L) },
            0
        ).value
        return !hasContent
    }

    /**
     * `PRAGMA foreign_key_check` is a belt-and-suspenders assert that the `foreign_keys=OFF` window
     * (content is inserted parent-first, so it shouldn't be needed) left the DB consistent. It
     * returns one row per violation; surface any, don't swallow them.
     */
    private fun checkForeignKeys() {
        val violations = mutableListOf<String>()
        driver.executeQuery(
            null,
            "PRAGMA foreign_key_check;",
            { cursor ->
                while (cursor.next().value) {
                    violations += "table=${cursor.getString(0)} rowid=${cursor.getLong(1)} " +
                        "parent=${cursor.getString(2)} fkid=${cursor.getLong(3)}"
                }
                QueryResult.Value(Unit)
            },
            0
        )
        check(violations.isEmpty()) {
            "withBulkLoad left foreign_key violations after the fresh seed: ${violations.joinToString("; ")}"
        }
    }

    companion object {
        /** Build over a driver whose database is freshly created (schema + installed version stamped). */
        fun createFresh(driver: SqlDriver): SqlDelightAppDatabase =
            SqlDelightAppDatabase(driver, createFreshOtterDatabase(driver))

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
            return SqlDelightAppDatabase(driver, buildOtterDatabase(driver))
        }
    }
}
