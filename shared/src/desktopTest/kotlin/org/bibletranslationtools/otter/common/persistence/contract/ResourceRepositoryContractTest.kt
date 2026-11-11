package org.bibletranslationtools.otter.common.persistence.contract

import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceRepositoryContractTest(mode: ContractMode) : RepositoryContract(mode) {

    private val repo: IResourceRepository get() = koin.get()

    private lateinit var notes: ResourceMetadata
    private lateinit var book: Collection
    private lateinit var chapter: Collection
    private lateinit var verse: Content
    private lateinit var note: Content

    @BeforeTest
    fun seed() {
        val chain = insertProjectChain()
        notes = insertMetadata(chain.language.id, identifier = "tn").model(chain.language)
        book = chain.project.model()
        chapter = insertCollection(chain.metadata.id, "gen-1", label = "chapter", parentFk = chain.project.id).model()
        verse = insertContent(chapter.id).model()
        val notesBook = insertCollection(notes.id, "gen-tn", label = "help")
        note = insertContent(notesBook.id, type = ContentType.TITLE).model()
    }

    @Test
    fun `a resource linked to a content is listed for that content and its metadata`() {
        via.completable({ repo.linkToContent(note, verse, notes.id) }, { repo.linkToContentSuspend(note, verse, notes.id) })

        val resources = via.list({ repo.getResources(verse, notes) }, { repo.getResourcesFlow(verse, notes) })

        assertEquals(listOf(note.id), resources.map { it.id })
        assertEquals(listOf(notes), repo.getResourceMetadata(verse))
        assertTrue(via.list({ repo.getResources(chapter, notes) }, { repo.getResourcesFlow(chapter, notes) }).isEmpty())
    }

    @Test
    fun `a resource linked to a collection is listed for that collection and its metadata`() {
        via.completable(
            { repo.linkToCollection(note, chapter, notes.id) },
            { repo.linkToCollectionSuspend(note, chapter, notes.id) }
        )

        val resources = via.list({ repo.getResources(chapter, notes) }, { repo.getResourcesFlow(chapter, notes) })

        assertEquals(listOf(note.id), resources.map { it.id })
        assertEquals(listOf(notes), repo.getResourceMetadata(chapter))
    }

    @Test
    fun `subtree resources are recorded up the tree from a linked content`() {
        via.completable({ repo.linkToContent(note, verse, notes.id) }, { repo.linkToContentSuspend(note, verse, notes.id) })
        assertTrue(repo.getSubtreeResourceMetadata(book).isEmpty())

        repo.calculateAndSetSubtreeHasResources(book.id)

        assertEquals(listOf(notes), repo.getSubtreeResourceMetadata(book))
        assertEquals(listOf(notes), repo.getSubtreeResourceMetadata(chapter))
    }

    @Test
    fun `getAll returns every content row`() {
        assertEquals(listOf(verse.id, note.id), via.single({ repo.getAll() }, { repo.getAllSuspend() }).map { it.id })
    }

    @Test
    fun `update rewrites the row and keeps it in its collection`() {
        val changed = note.copy(text = "changed")

        via.completable({ repo.update(changed) }, { repo.updateSuspend(changed) })

        val row = db.contentDao.fetchById(note.id)
        assertEquals("changed", row.text)
        assertEquals(db.collectionDao.fetchAll().single { it.slug == "gen-tn" }.id, row.collectionFk)
    }

    @Test
    fun `delete removes the row`() {
        via.completable({ repo.delete(note) }, { repo.deleteSuspend(note) })

        assertEquals(listOf(verse.id), db.contentDao.fetchAll().map { it.id })
    }
}
