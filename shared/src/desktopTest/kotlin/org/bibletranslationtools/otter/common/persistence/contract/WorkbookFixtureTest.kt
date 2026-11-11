package org.bibletranslationtools.otter.common.persistence.contract

import io.mockk.spyk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.DateHolder
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.database.dao.TakeDao
import org.bibletranslationtools.otter.common.persistence.database.dao.TranslationDao
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.TakeEntity
import java.io.File
import java.time.LocalDate
import kotlin.test.fail

/**
 * A workbook on a real database: an English source book with one chapter of two verses, a French
 * narration project derived from it, and the entity graph [IWorkbookRepository] builds over the two.
 *
 * Tests reach the entities through the methods here and through the entities' own helpers
 * (`insertTake`, `selectTake`, `getAllTakes`, `getSelectedTake`), and judge by database rows. Nothing
 * outside this class names a relay, so when the entities stop being relays only this class changes.
 *
 * The database is in a file, one connection per thread, as the apps run it. Saves are fire-and-forget
 * on io threads, so a row is waited for with [awaitDb].
 */
abstract class WorkbookFixtureTest : KoinDatabaseTest() {

    override val backend = JdbcBackend(inFile = true)

    /** Spies between the repositories and the database, to count writes and to make one fail. */
    protected lateinit var takeDao: TakeDao
    protected lateinit var translationDao: TranslationDao

    override fun daoProvider(): DaoProvider {
        takeDao = spyk(db.takeDao)
        translationDao = spyk(db.translationDao)
        return object : DaoProvider by db {
            override val takeDao get() = this@WorkbookFixtureTest.takeDao
            override val translationDao get() = this@WorkbookFixtureTest.translationDao
        }
    }

    protected val workbooks: IWorkbookRepository get() = koin.get()

    protected lateinit var source: Collection
    protected lateinit var project: Collection

    /** Builds the source and the derived project. Call before [openWorkbook]; add rows in between. */
    protected fun seedProject() {
        val english = insertLanguage("en", gateway = 1)
        val french = insertLanguage("fr")
        val ulb = insertMetadata(english.id).model(english)
        val genesis = insertCollection(ulb.id, "gen", label = "project")
        val chapter = insertCollection(ulb.id, "gen-1", label = "chapter", parentFk = genesis.id)
        db.contentDao.insert(content(chapter.id, db.contentTypeDao.fetchId(ContentType.META), sort = 0, label = "chapter"))
        insertContent(chapter.id, sort = 1, start = 1)
        insertContent(chapter.id, sort = 2, start = 2)
        source = genesis.model(ulb)
        project = koin.get<ICollectionRepository>()
            .deriveProject(listOf(ulb), source, french.model(), true, ProjectMode.NARRATION)
            .blockingGet()
    }

    protected fun openWorkbook(): Workbook = workbooks.get(source, project)

    protected fun Workbook.chapter(): Chapter = runBlocking { target.chaptersFlow.toList() }.single()

    /** The chunk of verse [verse], with the takes it has in the database loaded. */
    protected fun Workbook.verse(verse: Int): Chunk {
        val chunk = runBlocking { chapter().chunksSuspend() }.single { it.contentType == ContentType.TEXT && it.start == verse }
        awaitDb("the takes of verse $verse to load") { chunk.audio.getAllTakes().size == takeRows(verse).count { it.deletedTs == null } }
        return chunk
    }

    /** A take the user has just recorded: a file on disk, not yet in the workbook. */
    protected fun newTake(number: Int, verse: Int = 1): Take {
        val file = File(root, "v${verse}_t$number.wav").also { it.writeText("audio $verse $number") }
        return Take(file.name, file, number, MimeType.WAV, LocalDate.of(2024, 1, 1))
    }

    // ── what only this class knows how to do to an entity ────────────────────────────────

    protected fun delete(take: Take) = take.deletedTimestamp.accept(DateHolder.now())
    protected fun restore(take: Take) = take.deletedTimestamp.accept(DateHolder.empty)
    protected fun setChecking(take: Take, status: CheckingStatus) = take.checkingState.accept(TakeCheckingState(status, null))
    protected fun checkingInMemory(take: Take): CheckingStatus? = take.checkingState.value?.status

    // ── rows ─────────────────────────────────────────────────────────────────────────────

    /** The project's content row for verse [verse]. */
    protected fun verseRow(verse: Int): ContentEntity {
        val chapter = db.collectionDao.fetchChildren(db.collectionDao.fetchById(project.id)).single()
        return db.contentDao.fetchByCollectionId(chapter.id).single { it.labelKey == "verse" && it.start == verse }
    }

    /** Every take row of verse [verse], deleted ones included, by take number. */
    protected fun takeRows(verse: Int = 1): List<TakeEntity> =
        db.takeDao.fetchByContentId(verseRow(verse).id, includeDeleted = true).sortedBy { it.number }

    protected fun takeRow(take: Take, verse: Int = 1): TakeEntity? = takeRows(verse).singleOrNull { it.number == take.number }

    protected fun checkingOf(take: Take, verse: Int = 1): CheckingStatus? =
        takeRow(take, verse)?.let { db.checkingStatusDao.fetchById(it.checkingFk) }

    /** A take row written straight to the database, as a recording from an earlier session. */
    protected fun storedTake(number: Int, verse: Int = 1, selected: Boolean = false): TakeEntity {
        val file = File(root, "v${verse}_t$number.wav").also { it.writeText("audio $verse $number") }
        val row = verseRow(verse)
        val entity = take(row.id, db.checkingStatusDao.fetchId(CheckingStatus.UNCHECKED), number, file.name)
            .copy(filepath = file.toURI().path, createdTs = "2024-01-01")
        entity.id = db.takeDao.insert(entity)
        if (selected) db.contentDao.update(row.also { it.selectedTakeFk = entity.id })
        return entity
    }

    // ── waiting ──────────────────────────────────────────────────────────────────────────

    protected fun awaitDb(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    /** Long enough for a save that was going to happen to have happened. For asserting that none did. */
    protected fun settle() = Thread.sleep(300)

    /** Inserts [take] into [chunk] and waits until it is stored and selected, as the repository does on insert. */
    protected fun record(chunk: Chunk, take: Take, verse: Int = 1): TakeEntity {
        chunk.audio.insertTake(take)
        awaitDb("take ${take.number} to be stored and selected") {
            takeRow(take, verse)?.let { it.id == verseRow(verse).selectedTakeFk } == true
        }
        return takeRow(take, verse)!!
    }
}
