package org.bibletranslationtools.otter.common.persistence.contract

import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentRepositoryContractTest(mode: ContractMode) : RepositoryContract(mode) {

    private val repo: IContentRepository get() = koin.get()

    private lateinit var chapter: CollectionEntity

    @BeforeTest
    fun seed() {
        val chain = insertProjectChain()
        chapter = insertCollection(chain.metadata.id, "gen-1", label = "chapter", parentFk = chain.project.id)
    }

    private fun verse(n: Int, type: ContentType = ContentType.TEXT, bridged: Boolean = false) =
        Content(n, "verse", n, n, null, "text $n", "text/usfm", type, 1, bridged = bridged)

    private fun byCollection(collection: CollectionEntity = chapter) = via.single(
        { repo.getByCollection(collection.model()) },
        { repo.getByCollectionSuspend(collection.model()) }
    )

    @Test
    fun `insertForCollection gives each content its id and returns the chapter's contents`() {
        val contents = listOf(verse(1), verse(2), verse(3, bridged = true))

        val returned = via.single(
            { repo.insertForCollection(contents, chapter.model()) },
            { repo.insertForCollectionSuspend(contents, chapter.model()) }
        )

        assertEquals(listOf(1, 2, 3), contents.map { it.id })
        assertEquals(listOf(1, 2), returned.map { it.id }, "a bridged verse is stored but not returned")
        assertEquals(chapter.id, db.contentDao.fetchById(3).collectionFk)
    }

    @Test
    fun `getByCollection leaves out bridged content and carries the selected take with its markers`() {
        val first = insertContent(chapter.id, sort = 1, start = 1)
        insertContent(chapter.id, sort = 2, start = 2, bridged = true)
        val take = insertTake(first.id).also { db.takeDao.update(it.copy(createdTs = "2024-01-01")) }
        db.markerDao.insert(marker(take.id, position = 44))
        db.contentDao.update(first.also { it.selectedTakeFk = take.id })

        val content = byCollection().single()

        assertEquals(first.id, content.id)
        assertEquals(take.id, content.selectedTake?.id)
        assertEquals(listOf(44), content.selectedTake?.markers?.map { it.position })
    }

    @Test
    fun `getAll leaves out bridged content`() {
        insertContent(chapter.id, sort = 1, start = 1)
        insertContent(chapter.id, sort = 2, start = 2, bridged = true)

        assertEquals(listOf(1), via.single({ repo.getAll() }, { repo.getAllSuspend() }).map { it.start })
    }

    private fun meta() = via.single(
        { repo.getCollectionMetaContent(chapter.model()) },
        { repo.getCollectionMetaContentSuspend(chapter.model()) }
    )

    @Test
    fun `getCollectionMetaContent returns the chapter's meta content with the lowest start`() {
        insertContent(chapter.id, type = ContentType.META, sort = 0, start = 5)
        val first = insertContent(chapter.id, type = ContentType.META, sort = 1, start = 1)
        insertContent(chapter.id, type = ContentType.TEXT, sort = 2, start = 0)

        assertEquals(first.id, meta().id)
    }

    @Test
    fun `getCollectionMetaContent fails with an illegal state exception when the chapter has none`() {
        insertContent(chapter.id)

        assertFailsWith<IllegalStateException> { meta() }
    }

    private fun sourcesOf(content: Content) = via.single({ repo.getSources(content) }, { repo.getSourcesSuspend(content) })

    @Test
    fun `updateSources replaces the sources of a content`() {
        val derived = insertContent(chapter.id, sort = 1).model()
        val a = insertContent(chapter.id, sort = 2, start = 2).model()
        val b = insertContent(chapter.id, sort = 3, start = 3).model()

        via.completable({ repo.updateSources(derived, listOf(a, b)) }, { repo.updateSourcesSuspend(derived, listOf(a, b)) })
        assertEquals(listOf(a.id, b.id), sourcesOf(derived).map { it.id })

        via.completable({ repo.updateSources(derived, listOf(b)) }, { repo.updateSourcesSuspend(derived, listOf(b)) })
        assertEquals(listOf(b.id), sourcesOf(derived).map { it.id })
    }

    @Test
    fun `linkDerivedToSource links contents with the same label and type`() {
        val derivedVerse = insertContent(chapter.id, sort = 1).model()
        val sourceVerse = insertContent(chapter.id, sort = 2, start = 2).model()
        val sourceMeta = insertContent(chapter.id, type = ContentType.META, sort = 3).model()
        val sourceChunk = insertContent(chapter.id, sort = 4, start = 4).model().also { it.labelKey = "chunk" }

        val sources = listOf(sourceVerse, sourceMeta, sourceChunk)
        via.completable(
            { repo.linkDerivedToSource(listOf(derivedVerse), sources) },
            { repo.linkDerivedToSourceSuspend(listOf(derivedVerse), sources) }
        )

        assertEquals(listOf(sourceVerse.id), sourcesOf(derivedVerse).map { it.id })
    }

    @Test
    fun `deleteForCollection with a type removes that type only`() {
        insertContent(chapter.id, type = ContentType.META, sort = 0)
        insertContent(chapter.id, type = ContentType.TEXT, sort = 1)

        via.completable(
            { repo.deleteForCollection(chapter.model(), ContentType.TEXT) },
            { repo.deleteForCollectionSuspend(chapter.model(), ContentType.TEXT) }
        )

        assertEquals(listOf(ContentType.META), byCollection().map { it.type })
    }

    @Test
    fun `deleteForCollection without a type removes the content type whose id is 1`() {
        insertContent(chapter.id, type = ContentType.META, sort = 0)
        insertContent(chapter.id, type = ContentType.TEXT, sort = 1)
        val removed = db.contentTypeDao.fetchForId(1)

        // Not "everything": the DAO turns a missing type into type id 1.
        via.completable(
            { repo.deleteForCollection(chapter.model()) },
            { repo.deleteForCollectionSuspend(chapter.model()) }
        )

        assertEquals(listOf(ContentType.META, ContentType.TEXT) - removed, byCollection().map { it.type })
    }

    @Test
    fun `delete removes the row`() {
        val content = insertContent(chapter.id).model()

        via.completable({ repo.delete(content) }, { repo.deleteSuspend(content) })

        assertTrue(byCollection().isEmpty())
    }

    @Test
    fun `update rewrites the row and keeps it in its chapter`() {
        val content = insertContent(chapter.id).model().also { it.text = "changed" }

        via.completable({ repo.update(content) }, { repo.updateSuspend(content) })

        assertEquals("changed", byCollection().single().text)
        assertEquals(chapter.id, db.contentDao.fetchById(content.id).collectionFk)
    }

    @Test
    fun `updateAll rewrites each row and keeps it in its chapter`() {
        val contents = listOf(insertContent(chapter.id, sort = 1), insertContent(chapter.id, sort = 2, start = 2))
            .map { entity -> entity.model().also { it.draftNumber = 7 } }

        via.completable({ repo.updateAll(contents) }, { repo.updateAllSuspend(contents) })

        assertEquals(listOf(7, 7), byCollection().map { it.draftNumber })
    }

    @Test
    fun `a persistent connection holds the chapter's contents and follows an insert`() {
        insertContent(chapter.id)
        val connection = repo.getByCollectionWithPersistentConnection(chapter.model())
        assertEquals(listOf(1), connection.blockingFirst().map { it.start })

        via.single(
            { repo.insertForCollection(listOf(verse(2)), chapter.model()) },
            { repo.insertForCollectionSuspend(listOf(verse(2)), chapter.model()) }
        )

        assertEquals(listOf(1, 2), connection.blockingFirst().map { it.start })
    }
}
