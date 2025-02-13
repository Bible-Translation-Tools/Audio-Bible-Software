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
package org.bibletranslationtools.otter.common.api.persistence

import org.bibletranslationtools.otter_db.jooq.tables.InstalledEntity
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.bibletranslationtools.otter.common.persistence.database.DATABASE_INSTALLABLE_NAME
import org.bibletranslationtools.otter.common.persistence.database.SCHEMA_VERSION
import org.bibletranslationtools.otter.common.persistence.database.daos.*
import java.io.IOException
import java.io.InputStream

const val CREATION_SCRIPT = "sql/CreateAppDb.sql"

interface AppDatabase {
    val dsl: DSLContext

    fun setup(schemaFileStream: InputStream) {
        // Make sure the database file has the tables we need
        val sqlStatements = schemaFileStream
            .bufferedReader()
            .readText()
            .split(";")
            .filter { it.isNotBlank() }
            .map { "$it;" }

        // Execute each SQL statement
        sqlStatements.forEach {
            dsl.fetch(it)
        }

        dsl.insertInto(
            InstalledEntity.INSTALLED_ENTITY,
            InstalledEntity.INSTALLED_ENTITY.NAME,
            InstalledEntity.INSTALLED_ENTITY.VERSION
        ).values(
            DATABASE_INSTALLABLE_NAME,
            SCHEMA_VERSION
        ).execute()
    }

    // Create the DAOs
    val languageDao: LanguageDao
    val resourceMetadataDao: ResourceMetadataDao
    val collectionDao: CollectionDao
    val contentTypeDao: ContentTypeDao
    val contentDao: ContentDao
    val resourceLinkDao: ResourceLinkDao
    val subtreeHasResourceDao: SubtreeHasResourceDao
    val takeDao: TakeDao
    val markerDao: MarkerDao
    val installedEntityDao: InstalledEntityDao
    val translationDao: TranslationDao
    val versificationDao: VersificationDao
    val workbookTypeDao: WorkbookTypeDao
    val workbookDescriptorDao: WorkbookDescriptorDao
    val checkingStatusDao: CheckingStatusDao

    // Transaction support
    fun transaction(block: (DSLContext) -> Unit) {
        dsl.transaction { config ->
            // Create local transaction DSL and pass to block
            block(DSL.using(config))
        }
    }

    fun <T> transactionResult(block: (DSLContext) -> T): T {
        return dsl.transactionResult { config ->
            // Create local transaction DSL and pass to block
            block(DSL.using(config))
        }
    }
}

/*init {
    System.setProperty("org.jooq.no-logo", "true")
}

fun getDatabaseVersion(databaseFile: File): Int? {
    if (!databaseFile.exists() || databaseFile.length() == 0L) {
        return null
    }
    val sqliteDataSource = createSQLiteDataSource(databaseFile)
    val dsl = DSL.using(sqliteDataSource, SQLDialect.SQLITE)
    return try {
        dsl
            .select()
            .from(InstalledEntity.INSTALLED_ENTITY)
            .where(InstalledEntity.INSTALLED_ENTITY.NAME.eq(DATABASE_INSTALLABLE_NAME))
            .fetchSingle {
                it.get(InstalledEntity.INSTALLED_ENTITY.VERSION)
            }
    } catch (e: DataAccessException) {
        null
    }
}

private fun createSQLiteDataSource(databaseFile: File): SQLiteDataSource {
    val sqLiteDataSource = SQLiteDataSource()
    sqLiteDataSource.url = "jdbc:sqlite:${databaseFile.toURI().path}"
    sqLiteDataSource.config.toProperties().setProperty("foreign_keys", "true")
    return sqLiteDataSource
}
}*/
