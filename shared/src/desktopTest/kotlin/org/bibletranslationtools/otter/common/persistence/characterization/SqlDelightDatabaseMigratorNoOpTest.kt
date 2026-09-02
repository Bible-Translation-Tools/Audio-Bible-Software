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
package org.bibletranslationtools.otter.common.persistence.characterization

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.mockk
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightDatabaseMigrator
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5a fast sanity check (docs/phase5a-handoff.md): [SqlDelightDatabaseMigrator.migrate] must be
 * a no-op on an already-current (v14) database — proving the harness + version read work before the
 * real gate, the differential test in [SqlDelightDatabaseMigratorDifferentialTest], exercises the
 * actual v0->14 upgrade steps.
 */
class SqlDelightDatabaseMigratorNoOpTest {

    @Test
    fun `migrate is a no-op on a fresh v14 database`() {
        val file = File.createTempFile("migrator-noop-", ".sqlite")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            val db = SqlDelightAppDatabase.createFresh(driver)
            val seededId = db.languageDao.insert(
                LanguageEntity(0, "en", "English", "English", "ltr", 1, "US")
            )

            SqlDelightDatabaseMigrator(mockk<ITempFileProvider>(relaxed = true)).migrate(driver)

            driver.close()

            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT version FROM installed_entity WHERE name = 'DATABASE'").use { rs ->
                        assertTrue(rs.next(), "installed_entity row for DATABASE is missing")
                        assertEquals(14, rs.getInt("version"), "version must remain 14 after a no-op migrate")
                    }
                }
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT slug, name FROM language_entity WHERE id = $seededId").use { rs ->
                        assertTrue(rs.next(), "seeded language row did not survive the no-op migrate")
                        assertEquals("en", rs.getString("slug"))
                        assertEquals("English", rs.getString("name"))
                    }
                }
            }
        } finally {
            file.delete()
        }
    }
}
