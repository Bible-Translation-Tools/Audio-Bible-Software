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

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.bibletranslationtools.otter.db.OtterDatabase
import java.io.File

/**
 * Android's [DatabaseDriverFactory]. Drives [AndroidSqliteDriver] "dumb": our versioning lives in
 * installed_entity and SqlDelightDatabaseMigrator, not SQLDelight's automatic PRAGMA user_version
 * create/migrate, so onCreate/onUpgrade/onDowngrade are no-ops and SqlDelightAppDatabase.open owns
 * schema creation/migration itself. onConfigure enables foreign keys, matching the desktop
 * `PRAGMA foreign_keys=ON`.
 */
class AndroidDatabaseDriverFactory(private val context: Context) : DatabaseDriverFactory {
    override fun create(databaseFile: File): SqlDriver {
        // databaseDirectory == getDatabasePath("tr.db").parentFile, so databaseFile.name resolves to
        // getDatabasePath(databaseFile.name) == databaseFile — the same file existing installs have.
        val callback = object : AndroidSqliteDriver.Callback(OtterDatabase.Schema) {
            override fun onCreate(db: SupportSQLiteDatabase) { /* no-op: open()/createFresh owns schema */ }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) { /* no-op */ }
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        }
        return AndroidSqliteDriver(OtterDatabase.Schema, context, name = databaseFile.name, callback = callback)
    }
}
