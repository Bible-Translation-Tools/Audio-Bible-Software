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
package org.bibletranslationtools.otter.common.persistence.database.daos

import kotlin.test.Test
import kotlin.test.assertEquals
import org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight.SqlDelightLanguageDao
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.LanguageEntity
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.ResourceMetadataEntity
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.jooq.impl.DSL
import org.jooq.SQLDialect

class EquivalentDaoTest {

    @Test
    fun testLanguageDaoParallelVerification() {
        val entities = listOf(
            LanguageEntity(0, "en", "English", "English", "ltr", 1, "US"),
            LanguageEntity(0, "es", "Spanish", "Español", "ltr", 1, "ES"),
            LanguageEntity(0, "ar", "Arabic", "العربية", "rtl", 1, "SA")
        )

        // JOOQ Setup
        val jooqUrl = "jdbc:sqlite::memory:?jooq"
        val jooqDriver = JdbcSqliteDriver(jooqUrl)
        org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase.Schema.create(jooqDriver)
        val jooqDao = LanguageDao(DSL.using(jooqDriver.getConnection(), SQLDialect.SQLITE))

        // SqlDelight Setup
        val sdUrl = "jdbc:sqlite::memory:?sd"
        val sdDriver = JdbcSqliteDriver(sdUrl)
        org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase.Schema.create(sdDriver)
        val sdDatabase = org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase(sdDriver)
        val sqlDelightDao = SqlDelightLanguageDao(sdDatabase)

        // Test Insert & ID generation
        entities.forEach { entity ->
            val jooqId = jooqDao.insert(entity)
            val sdId = sqlDelightDao.insert(entity.copy(id = 0))
            assertEquals(jooqId, sdId, "Generated IDs should match for ${entity.slug}")
        }

        // Test Fetch All
        val jooqAll = jooqDao.fetchAll()
        val sdAll = sqlDelightDao.fetchAll()
        assertEquals(jooqAll.size, sdAll.size, "Count of entities should match")
        assertEquals(jooqAll, sdAll, "All entities should match")

        // Test Fetch Gateway
        assertEquals(jooqDao.fetchGateway(), sqlDelightDao.fetchGateway(), "Gateway entities should match")

        // Test Fetch By Slug
        val slug = "en"
        val jooqEn = jooqDao.fetchBySlug(slug)
        val sdEn = sqlDelightDao.fetchBySlug(slug)
        assertEquals(jooqEn, sdEn, "Entity by slug should match")

        // Test Update
        val updatedEn = jooqEn!!.copy(region = "UK")
        jooqDao.update(updatedEn)
        sqlDelightDao.update(updatedEn)
        assertEquals(jooqDao.fetchBySlug(slug), sqlDelightDao.fetchBySlug(slug), "Updated entity should match")

        // Test Delete
        jooqDao.delete(jooqEn)
        sqlDelightDao.delete(sdEn!!)
        assertEquals(jooqDao.fetchAll(), sqlDelightDao.fetchAll(), "Remaining entities should match after delete")
    }

    @Test
    fun testResourceMetadataDaoEquivalence() {
        val jooqUrl = "jdbc:sqlite::memory:?jooqRM"
        val jooqDriver = JdbcSqliteDriver(jooqUrl)
        org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase.Schema.create(jooqDriver)
        val jooqDao = ResourceMetadataDao(DSL.using(jooqDriver.getConnection(), SQLDialect.SQLITE))
        val jooqLangDao = LanguageDao(DSL.using(jooqDriver.getConnection(), SQLDialect.SQLITE))

        val sdUrl = "jdbc:sqlite::memory:?sdRM"
        val sdDriver = JdbcSqliteDriver(sdUrl)
        org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase.Schema.create(sdDriver)
        val sdDatabase = org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase(sdDriver)
        val sqlDelightDao = org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight.SqlDelightResourceMetadataDao(sdDatabase)
        val sdLangDao = org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight.SqlDelightLanguageDao(sdDatabase)

        // We need a language first in both
        val langEntity = LanguageEntity(0, "en", "English", "English", "ltr", 1, "US")
        val jooqLangId = jooqLangDao.insert(langEntity)
        val sdLangId = sdLangDao.insert(langEntity.copy(id = 0))
        assertEquals(jooqLangId, sdLangId, "Language IDs should match")

        val metadata = ResourceMetadataEntity(
            id = 0,
            conformsTo = "rc0.2",
            creator = "WA",
            description = "Test",
            format = "audio/wav",
            identifier = "ulb",
            issued = "2023-01-01",
            languageFk = jooqLangId,
            modified = "2023-01-01",
            publisher = "WA",
            subject = "Bible",
            type = "bundle",
            title = "ULB",
            version = "1.0",
            license = "CC BY-SA",
            path = "/tmp/test",
            derivedFromFk = null
        )

        val jooqId = jooqDao.insert(metadata)
        val sdId = sqlDelightDao.insert(metadata.copy(id = 0, languageFk = sdLangId))
        assertEquals(jooqId, sdId, "Generated Metadata IDs should match")

        val jooqFetched = jooqDao.fetchById(jooqId)
        val sdFetched = sqlDelightDao.fetchById(sdId)
        assertEquals(jooqFetched, sdFetched, "Fetched Metadata entities should match")

        // Test latest version
        val latestJooq = jooqDao.fetchLatestVersion("en", "ulb")
        val latestSd = sqlDelightDao.fetchLatestVersion("en", "ulb")
        assertEquals(latestJooq, latestSd, "Latest version should match")
    }

    @Test
    fun testCollectionDaoEquivalence() {
        val sdUrl = "jdbc:sqlite::memory:?sdColl"
        val sdDriver = JdbcSqliteDriver(sdUrl)
        org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase.Schema.create(sdDriver)
        val sdDatabase = org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase(sdDriver)
        val sqlDelightDao = org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight.SqlDelightCollectionDao(sdDatabase)
        
        val jooqUrl = "jdbc:sqlite::memory:?jooqColl"
        val jooqDriver = JdbcSqliteDriver(jooqUrl)
        org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase.Schema.create(jooqDriver)
        val jooqDao = CollectionDao(DSL.using(jooqDriver.getConnection(), SQLDialect.SQLITE))

        // Dependencies
        val langEntity = LanguageEntity(0, "en", "English", "English", "ltr", 1, "US")
        val sdLangId = org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight.SqlDelightLanguageDao(sdDatabase).insert(langEntity)
        val jooqLangId = LanguageDao(DSL.using(jooqDriver.getConnection(), SQLDialect.SQLITE)).insert(langEntity)

        val metadata = ResourceMetadataEntity(0, "rc0.2", "WA", "Test", "audio/wav", "ulb", "2023-01-01", sdLangId, "2023-01-01", "WA", "Bible", "bundle", "ULB", "1.0", "CC BY-SA", "/tmp/test", null)
        val sdDcId = org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight.SqlDelightResourceMetadataDao(sdDatabase).insert(metadata)
        val jooqDcId = ResourceMetadataDao(DSL.using(jooqDriver.getConnection(), SQLDialect.SQLITE)).insert(metadata.copy(languageFk = jooqLangId))

        val collection = org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.CollectionEntity(
            id = 0,
            parentFk = null,
            sourceFk = null,
            label = "project",
            title = "Matthew",
            slug = "mat",
            sort = 1,
            dublinCoreFk = sdDcId,
            modifiedTs = "2023-01-01"
        )

        val jooqId = jooqDao.insert(collection.copy(dublinCoreFk = jooqDcId))
        val sdId = sqlDelightDao.insert(collection.copy(dublinCoreFk = sdDcId))
        assertEquals(jooqId, sdId, "Generated Collection IDs should match")

        val jooqFetched = jooqDao.fetchById(jooqId)
        val sdFetched = sqlDelightDao.fetchById(sdId)
        // Adjust for IDs in the comparison
        assertEquals(jooqFetched!!.copy(dublinCoreFk = 0), sdFetched!!.copy(dublinCoreFk = 0), "Fetched Collection entities should match (ignoring DC FK)")
    }

    @Test
    fun testContentTypeDaoPrepopulation() {
        val sdUrl = "jdbc:sqlite::memory:?sdCT"
        val sdDriver = JdbcSqliteDriver(sdUrl)
        org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase.Schema.create(sdDriver)
        val sdDatabase = org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase(sdDriver)
        val sqlDelightDao = org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight.SqlDelightContentTypeDao(sdDatabase)

        val jooqUrl = "jdbc:sqlite::memory:?jooqCT"
        val jooqDriver = JdbcSqliteDriver(jooqUrl)
        org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase.Schema.create(jooqDriver)
        val jooqDao = ContentTypeDao(DSL.using(jooqDriver.getConnection(), SQLDialect.SQLITE))

        // JOOQ lazy loads and populates on first use
        // SqlDelight also lazy loads and populates on first use
        
        org.bibletranslationtools.otter.common.data.primitives.ContentType.entries.forEach { type ->
            val jooqId = jooqDao.fetchId(type)
            val sdId = sqlDelightDao.fetchId(type)
            assertEquals(jooqId, sdId, "IDs for ContentType ${type.name} should match")
        }
    }
}
