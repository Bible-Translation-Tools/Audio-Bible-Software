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

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.bibletranslationtools.otter.common.persistence.entities.MarkerEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.otter.common.persistence.entities.TakeEntity
import org.bibletranslationtools.otter.common.persistence.entities.TranslationEntity
import org.bibletranslationtools.otter.common.persistence.entities.WorkbookDescriptorEntity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Backend-agnostic characterization of the whole DAO surface, written against [IAppDatabase] and
 * therefore runnable against ANY implementation of it. This is the load-bearing safety net for the
 * jOOQ → SQLDelight migration (docs/jooq-to-sqldelight-migration-plan.md).
 *
 * The differential design: this class asserts fixed, hand-derived expected values for every DAO
 * behavior that matters — including the quirks (`insert` returning `SELECT max(id)`, `insertAll`
 * returning a contiguous id range, lazy enum-table seeding, boolean↔int, nested-subquery result
 * sets, the three `fetchLatestVersion` overloads). A subclass supplies a backend via
 * [createDatabase]. Today the only subclass builds the jOOQ backend; Phase 3 adds a SQLDelight
 * subclass, and because both run these identical assertions, green-on-both IS the proof of
 * functional identity. (Phase 3 also adds a direct jooq-vs-sqldelight comparator for result sets
 * whose expected value is impractical to hardcode.)
 *
 * Each DAO's tests live in a reusable abstract subclass (e.g. `LanguageDaoCharacterization`);
 * a concrete class per backend supplies [backend] and nothing else, e.g.
 * `class JooqLanguageDaoCharacterizationTest : LanguageDaoCharacterization() { override val backend = JooqBackend }`.
 */
abstract class AbstractDatabaseCharacterizationTest {

    /** Builds and tears down a fresh, empty database (schema v14) for one backend. */
    interface DatabaseBackend {
        fun createDatabase(): DaoProvider
        fun destroyDatabase(db: DaoProvider)
    }

    /** Supplied by the concrete per-backend subclass. */
    protected abstract val backend: DatabaseBackend

    protected lateinit var db: DaoProvider

    @BeforeTest
    fun setUp() {
        db = backend.createDatabase()
    }

    @AfterTest
    fun tearDown() {
        if (::db.isInitialized) backend.destroyDatabase(db)
    }

    // ── Fixture builders ──────────────────────────────────────────────────────────────────────
    // A valid FK graph is language → dublin_core (metadata) → collection → content → take.
    // These insert the minimum chain and return the persisted ids so tests can build on them.

    protected fun installable(name: String, version: Int) = object : Installable {
        override val name = name
        override val version = version
        override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable =
            Completable.complete()
    }

    protected fun language(
        slug: String,
        name: String = slug.uppercase(),
        anglicized: String = name,
        direction: String = "ltr",
        gateway: Int = 0,
        region: String = "region",
    ) = LanguageEntity(0, slug, name, anglicized, direction, gateway, region)

    protected fun insertLanguage(slug: String, gateway: Int = 0): LanguageEntity {
        val entity = language(slug, gateway = gateway)
        entity.id = db.languageDao.insert(entity)
        return entity
    }

    protected fun metadata(
        languageFk: Int,
        identifier: String = "ulb",
        version: String = "1",
        creator: String = "creator",
        derivedFromFk: Int? = null,
    ) = ResourceMetadataEntity(
        id = 0,
        conformsTo = "rc0.2",
        creator = creator,
        description = "desc",
        format = "text/usfm",
        identifier = identifier,
        issued = "2024-01-01",
        languageFk = languageFk,
        modified = "2024-01-01",
        publisher = "pub",
        subject = "Bible",
        type = "book",
        title = "Title",
        version = version,
        license = "",
        path = "/path/$identifier-$version",
        derivedFromFk = derivedFromFk,
    )

    protected fun insertMetadata(
        languageFk: Int,
        identifier: String = "ulb",
        version: String = "1",
        creator: String = "creator",
        derivedFromFk: Int? = null,
    ): ResourceMetadataEntity {
        val entity = metadata(languageFk, identifier, version, creator, derivedFromFk)
        entity.id = db.resourceMetadataDao.insert(entity)
        return entity
    }

    protected fun collection(
        dublinCoreFk: Int?,
        slug: String,
        label: String = "project",
        title: String = slug,
        sort: Int = 1,
        parentFk: Int? = null,
        sourceFk: Int? = null,
    ) = CollectionEntity(0, parentFk, sourceFk, label, title, slug, sort, dublinCoreFk, null)

    protected fun insertCollection(
        dublinCoreFk: Int?,
        slug: String,
        label: String = "project",
        sort: Int = 1,
        parentFk: Int? = null,
        sourceFk: Int? = null,
    ): CollectionEntity {
        val entity = collection(dublinCoreFk, slug, label, slug, sort, parentFk, sourceFk)
        entity.id = db.collectionDao.insert(entity)
        return entity
    }

    protected fun content(
        collectionFk: Int,
        typeFk: Int,
        sort: Int = 1,
        start: Int = 1,
        end: Int = start,
        label: String = "verse",
        text: String? = "text",
        bridged: Boolean = false,
    ) = ContentEntity(
        id = 0,
        sort = sort,
        labelKey = label,
        start = start,
        end = end,
        collectionFk = collectionFk,
        selectedTakeFk = null,
        text = text,
        format = "text/usfm",
        type_fk = typeFk,
        draftNumber = 1,
        bridged = bridged,
    )

    protected fun insertContent(
        collectionFk: Int,
        type: ContentType = ContentType.TEXT,
        sort: Int = 1,
        start: Int = 1,
        end: Int = start,
        bridged: Boolean = false,
    ): ContentEntity {
        val entity = content(collectionFk, db.contentTypeDao.fetchId(type), sort, start, end, bridged = bridged)
        entity.id = db.contentDao.insert(entity)
        return entity
    }

    protected fun take(
        contentFk: Int,
        checkingFk: Int,
        number: Int = 1,
        filename: String = "take$number.wav",
        deletedTs: String? = null,
    ) = TakeEntity(
        id = 0,
        contentFk = contentFk,
        filename = filename,
        filepath = "/takes/$filename",
        number = number,
        createdTs = "2024-01-01T00:00:00",
        deletedTs = deletedTs,
        played = 0,
        checkingFk = checkingFk,
        checksum = null,
    )

    protected fun insertTake(contentFk: Int, number: Int = 1, deletedTs: String? = null): TakeEntity {
        val checkingFk = db.checkingStatusDao.fetchId(CheckingStatus.UNCHECKED)
        val entity = take(contentFk, checkingFk, number, deletedTs = deletedTs)
        entity.id = db.takeDao.insert(entity)
        return entity
    }

    protected fun marker(takeFk: Int, number: Int = 1, position: Int = 0, label: String = "1") =
        MarkerEntity(0, takeFk, number, position, label)

    protected fun translation(sourceFk: Int, targetFk: Int) =
        TranslationEntity(0, sourceFk, targetFk, null, 1.0, 1.0)

    protected fun workbookDescriptor(sourceFk: Int, targetFk: Int, mode: ProjectMode = ProjectMode.TRANSLATION) =
        WorkbookDescriptorEntity(0, sourceFk, targetFk, db.workbookTypeDao.fetchId(mode))

    /** language → metadata → project collection, returning the project collection. */
    protected fun insertProjectChain(langSlug: String = "en", projectSlug: String = "gen"): ProjectChain {
        val lang = insertLanguage(langSlug, gateway = 1)
        val meta = insertMetadata(lang.id)
        val project = insertCollection(meta.id, projectSlug, label = "project", sort = 1)
        return ProjectChain(lang, meta, project)
    }

    protected data class ProjectChain(
        val language: LanguageEntity,
        val metadata: ResourceMetadataEntity,
        val project: CollectionEntity,
    )
}
