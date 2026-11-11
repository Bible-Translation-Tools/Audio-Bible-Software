package org.bibletranslationtools.otter.common.persistence.contract

import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Marker
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import java.io.File
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TakeRepositoryContractTest(mode: ContractMode) : RepositoryContract(mode) {

    private val repo: ITakeRepository get() = koin.get()

    private lateinit var project: CollectionEntity
    private lateinit var chapter: CollectionEntity
    private lateinit var verse: ContentEntity

    @BeforeTest
    fun seed() {
        val chain = insertProjectChain()
        project = chain.project
        chapter = insertCollection(chain.metadata.id, "gen-1", label = "chapter", parentFk = project.id)
        verse = insertContent(chapter.id)
    }

    /** A take whose file exists unless [exists] is false. Not yet in the database. */
    private fun take(number: Int, deleted: LocalDate? = null, markers: List<Marker> = emptyList(), exists: Boolean = true): Take {
        val file = File(root, "take$number.wav")
        if (exists) file.writeText("audio")
        return Take(file.name, file, number, LocalDate.of(2024, 1, 1), deleted, false, CheckingStatus.UNCHECKED, null, markers)
    }

    private fun insert(take: Take, content: ContentEntity = verse): Take {
        val id = via.single({ repo.insertForContent(take, content.model()) }, { repo.insertForContentSuspend(take, content.model()) })
        return take.copy(id = id)
    }

    private fun byContent(includeDeleted: Boolean = false) = via.single(
        { repo.getByContent(verse.model(), includeDeleted) },
        { repo.getByContentSuspend(verse.model(), includeDeleted) }
    )

    @Test
    fun `insertForContent returns the new id and stores the take with its markers`() {
        val stored = insert(take(1, markers = listOf(Marker(1, 100, "1"), Marker(2, 200, "2"))))

        assertEquals(1, stored.id)
        val read = byContent().single()
        assertEquals(stored.copy(markers = emptyList()), read.copy(markers = emptyList()))
        assertEquals(listOf(Marker(1, 100, "1", 1), Marker(2, 200, "2", 2)), read.markers)
    }

    @Test
    fun `getByContent leaves out deleted takes unless asked for them`() {
        insert(take(1))
        insert(take(2, deleted = LocalDate.of(2024, 2, 2)))

        assertEquals(listOf(1), byContent().map { it.number })
        assertEquals(listOf(1, 2), byContent(includeDeleted = true).map { it.number })
    }

    @Test
    fun `getAll returns every take, deleted ones included`() {
        insert(take(1))
        insert(take(2, deleted = LocalDate.of(2024, 2, 2)))

        assertEquals(listOf(1, 2), via.single({ repo.getAll() }, { repo.getAllSuspend() }).map { it.number })
    }

    @Test
    fun `update rewrites the take, replaces its markers and keeps it on its content`() {
        val stored = insert(take(1, markers = listOf(Marker(1, 100, "1"))))
        val changed = stored.copy(played = true, checkingStatus = CheckingStatus.VERSE, markers = listOf(Marker(1, 150, "1")))

        via.completable({ repo.update(changed) }, { repo.updateSuspend(changed) })

        val read = byContent().single()
        assertTrue(read.played)
        assertEquals(CheckingStatus.VERSE, read.checkingStatus)
        assertEquals(listOf(150), read.markers.map { it.position })
        assertEquals(verse.id, db.takeDao.fetchById(stored.id).contentFk)
    }

    @Test
    fun `delete removes the row and leaves the file`() {
        val stored = insert(take(1))

        via.completable({ repo.delete(stored) }, { repo.deleteSuspend(stored) })

        assertTrue(byContent(includeDeleted = true).isEmpty())
        assertTrue(stored.path.exists())
    }

    @Test
    fun `deleteForContent removes every take of the content, deleted ones included`() {
        insert(take(1))
        insert(take(2, deleted = LocalDate.of(2024, 2, 2)))
        val other = insertContent(chapter.id, sort = 2, start = 2)
        insert(take(3), other)

        via.completable({ repo.deleteForContent(verse.model()) }, { repo.deleteForContentSuspend(verse.model()) })

        assertEquals(listOf(3), db.takeDao.fetchAll().map { it.number })
    }

    @Test
    fun `markDeleted fails on its foreign key and leaves the take as it was`() {
        val stored = insert(take(1))

        // It writes content_fk = -1, the mapper's default. Nothing in the apps calls it.
        val failure = runCatching { via.completable({ repo.markDeleted(stored) }, { repo.markDeletedSuspend(stored) }) }

        assertTrue(failure.isFailure)
        assertNull(db.takeDao.fetchById(stored.id).deletedTs)
    }

    @Test
    fun `removeNonExistentTakes deletes a take whose file is gone and clears its selection`() {
        val kept = insert(take(1))
        val gone = insert(take(2, exists = false))
        db.contentDao.update(db.contentDao.fetchById(verse.id).also { it.selectedTakeFk = gone.id })

        via.completable({ repo.removeNonExistentTakes() }, { repo.removeNonExistentTakesSuspend() })

        assertEquals(listOf(kept.id), db.takeDao.fetchAll().map { it.id })
        assertNull(db.contentDao.fetchById(verse.id).selectedTakeFk)
    }

    @Test
    fun `getSoftDeletedTakes returns the deleted takes under a project's chapters`() {
        insert(take(1))
        insert(take(2, deleted = LocalDate.of(2024, 2, 2)))

        val deleted = via.single(
            { repo.getSoftDeletedTakes(project.model()) },
            { repo.getSoftDeletedTakesSuspend(project.model()) }
        )

        assertEquals(listOf(2), deleted.map { it.number })
    }

    @Test
    fun `deleteExpiredTakes with no expiry removes a take deleted today, and its file`() {
        val live = insert(take(1))
        val deleted = insert(take(2, deleted = LocalDate.now()))

        via.completable({ repo.deleteExpiredTakes() }, { repo.deleteExpiredTakesSuspend() })

        assertEquals(listOf(live.id), db.takeDao.fetchAll().map { it.id })
        assertFalse(deleted.path.exists())
        assertTrue(live.path.exists())
    }

    @Test
    fun `deleteExpiredTakes keeps a take deleted more recently than the expiry`() {
        val recent = insert(take(1, deleted = LocalDate.now().minusDays(1)))
        val old = insert(take(2, deleted = LocalDate.now().minusDays(5)))

        via.completable({ repo.deleteExpiredTakes(5) }, { repo.deleteExpiredTakesSuspend(5) })

        assertEquals(listOf(recent.id), db.takeDao.fetchAll().map { it.id })
        assertTrue(recent.path.exists())
        assertFalse(old.path.exists())
    }

    @Test
    fun `getByCollection returns the takes of a chapter's contents`() {
        insert(take(1))
        insert(take(2, deleted = LocalDate.of(2024, 2, 2)))
        val otherChapter = insertCollection(project.dublinCoreFk, "gen-2", label = "chapter", sort = 2, parentFk = project.id)
        insert(take(3), insertContent(otherChapter.id))

        fun get(includeDeleted: Boolean) = via.single(
            { repo.getByCollection(chapter.model(), includeDeleted) },
            { repo.getByCollectionSuspend(chapter.model(), includeDeleted) }
        ).map { it.number }

        assertEquals(listOf(1), get(includeDeleted = false))
        assertEquals(listOf(1, 2), get(includeDeleted = true))
    }

    @Test
    fun `getContentType returns the type of the take's content`() {
        val meta = insertContent(chapter.id, type = ContentType.META, sort = 0)
        val stored = insert(take(1), meta)

        assertEquals(ContentType.META, via.single({ repo.getContentType(stored) }, { repo.getContentTypeSuspend(stored) }))
    }
}
