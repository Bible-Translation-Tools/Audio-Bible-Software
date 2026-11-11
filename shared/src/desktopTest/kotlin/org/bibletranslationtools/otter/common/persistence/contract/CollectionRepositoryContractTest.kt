package org.bibletranslationtools.otter.common.persistence.contract

import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionRepositoryContractTest(mode: ContractMode) : RepositoryContract(mode) {

    private val repo: ICollectionRepository get() = koin.get()

    private lateinit var english: LanguageEntity
    private lateinit var french: LanguageEntity
    private lateinit var ulb: ResourceMetadata
    private lateinit var bible: CollectionEntity
    private lateinit var genesis: CollectionEntity
    private lateinit var chapter: CollectionEntity

    /** A source: bible > genesis > chapter 1, with a chapter title and one verse. */
    @BeforeTest
    fun seed() {
        english = insertLanguage("en", gateway = 1)
        french = insertLanguage("fr")
        val metadata = insertMetadata(english.id)
        ulb = metadata.model(english)
        bible = insertCollection(metadata.id, "bible", label = "bundle")
        genesis = insertCollection(metadata.id, "gen", label = "project", parentFk = bible.id)
        chapter = insertCollection(metadata.id, "gen-1", label = "chapter", parentFk = genesis.id)
        // The chapter's meta content is found by its label, not its type.
        db.contentDao.insert(content(chapter.id, db.contentTypeDao.fetchId(ContentType.META), sort = 0, label = "chapter"))
        insertContent(chapter.id, type = ContentType.TEXT, sort = 1)
    }

    private fun all() = via.single({ repo.getAll() }, { repo.getAllSuspend() })

    private fun derive(verseByVerse: Boolean, mode: ProjectMode = ProjectMode.NARRATION): Collection = via.single(
        { repo.deriveProject(listOf(ulb), genesis.model(ulb), french.model(), verseByVerse, mode) },
        { repo.deriveProjectSuspend(listOf(ulb), genesis.model(ulb), french.model(), verseByVerse, mode) }
    )

    @Test
    fun `insert returns the new id and getAll returns each collection with its metadata`() {
        val exodus = Collection(2, "exo", "project", "Exodus", ulb)

        val id = via.single({ repo.insert(exodus) }, { repo.insertSuspend(exodus) })

        assertEquals(4, id)
        assertEquals(listOf("bible", "gen", "gen-1", "exo"), all().map { it.slug })
        assertEquals(exodus.copy(id = id), all().last())
    }

    @Test
    fun `delete removes the row and, through its foreign keys, what hangs under it`() {
        via.completable({ repo.delete(genesis.model(ulb)) }, { repo.deleteSuspend(genesis.model(ulb)) })

        assertEquals(listOf("bible"), all().map { it.slug })
        assertTrue(db.contentDao.fetchAll().isEmpty())
    }

    private fun project(id: Int) = via.maybe({ repo.getProject(id) }, { repo.getProjectSuspend(id) })

    @Test
    fun `getProject returns the collection with its metadata`() {
        assertEquals(genesis.model(ulb), project(genesis.id))
    }

    @Test
    fun `getProject fails for an id that is not stored`() {
        assertFailsWith<NullPointerException> { project(99) }
    }

    @Test
    fun `getProjects returns the stored collections by id and skips unknown ids`() {
        val ids = listOf(genesis.id, chapter.id, genesis.id, 99)

        // No suspend twin yet, so every mode runs the Rx method. Phase 2 adds the twin and the binding.
        val projects = repo.getProjects(ids).blockingGet()

        assertEquals(mapOf(genesis.id to genesis.model(ulb), chapter.id to chapter.model(ulb)), projects)
        assertTrue(repo.getProjects(emptyList()).blockingGet().isEmpty())
    }

    private fun derivedOf(source: Collection) =
        via.maybe({ repo.getDerivedProject(source) }, { repo.getDerivedProjectSuspend(source) })

    private fun sourceOf(project: Collection) = via.maybe({ repo.getSource(project) }, { repo.getSourceSuspend(project) })

    @Test
    fun `a derived project and its source find each other, and a source has neither`() {
        val derived = derive(verseByVerse = true)

        assertEquals(derived.id, derivedOf(genesis.model(ulb))?.id)
        assertEquals(genesis.model(ulb), sourceOf(derived))
        assertNull(derivedOf(derived))
        assertNull(sourceOf(genesis.model(ulb)))
    }

    @Test
    fun `getDerivedProject and getSource report a database failure as absent`() {
        val derived = derive(verseByVerse = true)
        backend.destroyDatabase(db)

        assertNull(derivedOf(genesis.model(ulb)))
        assertNull(sourceOf(derived))
    }

    @Test
    fun `projects are listed as derived, source or root`() {
        val derived = derive(verseByVerse = true)

        assertEquals(
            listOf(derived.id),
            via.single({ repo.getDerivedProjects() }, { repo.getDerivedProjectsSuspend() }).map { it.id }
        )
        assertEquals(
            listOf(genesis.id),
            via.single({ repo.getSourceProjects() }, { repo.getSourceProjectsSuspend() }).map { it.id }
        )
        assertEquals(
            listOf(bible.id),
            via.single({ repo.getRootSources() }, { repo.getRootSourcesSuspend() }).map { it.id }
        )
    }

    @Test
    fun `getChildren returns the direct children`() {
        assertEquals(
            listOf(genesis.model(ulb)),
            via.single({ repo.getChildren(bible.model(ulb)) }, { repo.getChildrenSuspend(bible.model(ulb)) })
        )
    }

    @Test
    fun `getProjectBySlugAndMetadata returns the match and fails with a null pointer exception without one`() {
        fun get(slug: String) = via.single(
            { repo.getProjectBySlugAndMetadata(slug, ulb) },
            { repo.getProjectBySlugAndMetadataSuspend(slug, ulb) }
        )

        assertEquals(genesis.model(ulb), get("gen"))
        assertFailsWith<NullPointerException> { get("exo") }
    }

    @Test
    fun `updateSource and updateParent repoint the row`() {
        val exodus = insertCollection(ulb.id, "exo", sort = 2).model(ulb)

        via.completable({ repo.updateSource(exodus, genesis.model()) }, { repo.updateSourceSuspend(exodus, genesis.model()) })
        via.completable({ repo.updateParent(exodus, bible.model()) }, { repo.updateParentSuspend(exodus, bible.model()) })

        val row = db.collectionDao.fetchById(exodus.id)
        assertEquals(genesis.id, row.sourceFk)
        assertEquals(bible.id, row.parentFk)
    }

    @Test
    fun `update rewrites the row and keeps its parent and source`() {
        val changed = genesis.model(ulb).copy(titleKey = "Genesis")

        via.completable({ repo.update(changed) }, { repo.updateSuspend(changed) })

        val row = db.collectionDao.fetchById(genesis.id)
        assertEquals("Genesis", row.title)
        assertEquals(bible.id, row.parentFk)
    }

    @Test
    fun `deriveProject verse by verse copies chapters and all content, and records the workbook`() {
        val derived = derive(verseByVerse = true)

        assertEquals("gen", derived.slug)
        assertEquals("fr", derived.resourceContainer?.language?.slug)
        assertTrue(derived.resourceContainer!!.path.exists(), "the derived resource container is written")
        val row = db.collectionDao.fetchById(derived.id)
        assertEquals(genesis.id, row.sourceFk)
        val derivedChapter = db.collectionDao.fetchChildren(row).single()
        assertEquals(chapter.id, derivedChapter.sourceFk)
        assertEquals(
            listOf(ContentType.META, ContentType.TEXT),
            db.contentDao.fetchByCollectionId(derivedChapter.id).map { db.contentTypeDao.fetchForId(it.type_fk) }
        )
        val descriptor = db.workbookDescriptorDao.fetchAll().single()
        assertEquals(genesis.id to derived.id, descriptor.sourceFk to descriptor.targetFk)
    }

    @Test
    fun `deriveProject not verse by verse copies only the chapter's meta content`() {
        val derived = derive(verseByVerse = false, mode = ProjectMode.TRANSLATION)

        val derivedChapter = db.collectionDao.fetchChildren(db.collectionDao.fetchById(derived.id)).single()
        assertEquals(
            listOf(ContentType.META),
            db.contentDao.fetchByCollectionId(derivedChapter.id).map { db.contentTypeDao.fetchForId(it.type_fk) }
        )
    }

    @Test
    fun `deriveProject twice returns the same project and adds nothing`() {
        val first = derive(verseByVerse = true)
        val rows = db.collectionDao.fetchAll().size to db.contentDao.fetchAll().size

        val second = derive(verseByVerse = true)

        assertEquals(first.id, second.id)
        assertEquals(rows, db.collectionDao.fetchAll().size to db.contentDao.fetchAll().size)
        assertEquals(1, db.workbookDescriptorDao.fetchAll().size)
    }

    @Test
    fun `deriveProjects derives every child of the root`() {
        insertCollection(ulb.id, "exo", sort = 2, parentFk = bible.id)

        val derived = via.single(
            { repo.deriveProjects(bible.model(ulb), french.model(), true, ProjectMode.NARRATION) },
            { repo.deriveProjectsSuspend(bible.model(ulb), french.model(), true, ProjectMode.NARRATION) }
        )

        assertEquals(listOf("gen", "exo"), derived.map { it.slug })
        assertEquals(2, db.workbookDescriptorDao.fetchAll().size)
    }

    private fun deleteProject(project: Collection) = via.completable(
        { repo.deleteProject(project, deleteAudio = true) },
        { repo.deleteProjectSuspend(project, deleteAudio = true) }
    )

    @Test
    fun `deleteProject removes the last project of a derived container together with the container`() {
        val derived = derive(verseByVerse = true)
        val container = derived.resourceContainer!!

        deleteProject(derived)

        assertEquals(listOf("bible", "gen", "gen-1"), all().map { it.slug })
        assertFalse(container.path.exists())
        assertNull(db.resourceMetadataDao.fetchById(container.id))
    }

    @Test
    fun `deleteProject removes only the row when the project's container cannot be read`() {
        deleteProject(genesis.model(ulb))

        assertEquals(listOf("bible"), all().map { it.slug })
        assertEquals(ulb.id, db.resourceMetadataDao.fetchById(ulb.id)?.id, "the metadata row stays")
    }

    @Test
    fun `deleteResources leaves the project's own takes alone`() {
        val derived = derive(verseByVerse = true)
        val derivedChapter = db.collectionDao.fetchChildren(db.collectionDao.fetchById(derived.id)).single()
        val take = insertTake(db.contentDao.fetchByCollectionId(derivedChapter.id).last().id)

        via.completable({ repo.deleteResources(derived, true) }, { repo.deleteResourcesSuspend(derived, true) })

        assertEquals(listOf(take.id), db.takeDao.fetchAll().map { it.id })
    }

    @Test
    fun `collectionsWithoutTakes lists the chapters where no content of type id 1 has a take`() {
        val second = insertCollection(ulb.id, "gen-2", label = "chapter", sort = 2, parentFk = genesis.id)
        val typeOne = db.contentTypeDao.fetchForId(1)!!
        insertTake(insertContent(second.id, type = typeOne).id)

        val without = via.single(
            { repo.collectionsWithoutTakes(genesis.model(ulb)) },
            { repo.collectionsWithoutTakesSuspend(genesis.model(ulb)) }
        )

        assertEquals(listOf("gen-1"), without.map { it.slug })
    }
}
