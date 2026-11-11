package org.bibletranslationtools.otter.common.persistence.contract

import io.mockk.every
import io.mockk.verify
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the workbook entity graph does to the database today, written before any of it is ported.
 *
 * Several of these pin behavior the migration audit lists as a defect. They are marked with the
 * defect's number. A test that pins a defect changes in the same change that fixes it.
 */
class WorkbookRepositoryCharacterizationTest : WorkbookFixtureTest() {

    @BeforeTest
    fun seed() = seedProject()

    @Test
    fun `loading a workbook leaves every row as it was`() {
        storedTake(1)
        storedTake(2, selected = true)
        val takes = takeRows()
        val content = verseRow(1)

        val chunk = openWorkbook().verse(1)
        settle()

        assertEquals(2, chunk.audio.getAllTakes().size)
        assertEquals(2, chunk.audio.getSelectedTake()?.number)
        assertEquals(takes, takeRows())
        assertEquals(content, verseRow(1))
    }

    @Test
    fun `inserting a take stores one row and selects it`() {
        val chunk = openWorkbook().verse(1)
        val take = newTake(1)

        val row = record(chunk, take)

        assertEquals(listOf(row), takeRows())
        assertEquals(take.file.name, row.filename)
        assertTrue(row.id > 0)
        assertEquals(take, chunk.audio.getSelectedTake())
        assertEquals(listOf(take), chunk.audio.getAllTakes().toList())
    }

    @Test
    fun `inserting a second take selects it in place of the first`() {
        val chunk = openWorkbook().verse(1)
        record(chunk, newTake(1))

        val second = record(chunk, newTake(2))

        assertEquals(listOf(1, 2), takeRows().map { it.number })
        assertEquals(second.id, verseRow(1).selectedTakeFk)
    }

    @Test
    fun `selecting a take stores the selection, and selecting nothing clears it`() {
        val chunk = openWorkbook().verse(1)
        val first = newTake(1)
        val firstRow = record(chunk, first)
        record(chunk, newTake(2))

        chunk.audio.selectTake(first)
        assertEquals(first, chunk.audio.getSelectedTake(), "the selection reads back at once")
        awaitDb("the selection to be stored") { verseRow(1).selectedTakeFk == firstRow.id }

        chunk.audio.selectTake(null)
        awaitDb("the selection to be cleared") { verseRow(1).selectedTakeFk == null }
    }

    @Test
    fun `deleting the selected take deselects it before the call returns, then stores both`() {
        val chunk = openWorkbook().verse(1)
        val take = newTake(1)
        record(chunk, take)

        delete(take)

        assertNull(chunk.audio.getSelectedTake(), "deselected on the caller's stack")
        assertTrue(take.isDeleted())
        awaitDb("the delete to be stored") { takeRow(take)?.deletedTs != null }
        awaitDb("the deselect to be stored") { verseRow(1).selectedTakeFk == null }
        assertTrue(take.file.exists(), "the file stays until the purge")
    }

    @Test
    fun `deleting a take that is not selected leaves the selection alone`() {
        val chunk = openWorkbook().verse(1)
        val first = newTake(1)
        record(chunk, first)
        val secondRow = record(chunk, newTake(2))

        delete(first)

        awaitDb("the delete to be stored") { takeRow(first)?.deletedTs != null }
        assertEquals(2, chunk.audio.getSelectedTake()?.number)
        assertEquals(secondRow.id, verseRow(1).selectedTakeFk)
    }

    /** Relay defect 1. `UndoTakeDeleteTest` in the integration tier shows what this costs the user. */
    @Test
    fun `undoing a delete is not stored - the row stays deleted`() {
        val chunk = openWorkbook().verse(1)
        val take = newTake(1)
        record(chunk, take)
        delete(take)
        awaitDb("the delete to be stored") { takeRow(take)?.deletedTs != null }

        restore(take)
        settle()

        assertTrue(!take.isDeleted(), "in memory the take is back")
        assertNotNull(takeRow(take)?.deletedTs, "defect 1: the row is still deleted")
    }

    @Test
    fun `a checking status change is stored on the take row`() {
        val chunk = openWorkbook().verse(1)
        val take = newTake(1)
        record(chunk, take)

        setChecking(take, CheckingStatus.VERSE)

        awaitDb("the status to be stored") { checkingOf(take) == CheckingStatus.VERSE }
        assertEquals(takeRow(take)?.id, verseRow(1).selectedTakeFk, "the selection is kept")
    }

    @Test
    fun `checking status changes are stored in the order they were made`() {
        val chunk = openWorkbook().verse(1)
        val take = newTake(1)
        record(chunk, take)

        repeat(20) {
            setChecking(take, CheckingStatus.PEER_EDIT)
            setChecking(take, CheckingStatus.KEYWORD)
            setChecking(take, CheckingStatus.VERSE)
        }
        setChecking(take, CheckingStatus.PEER_EDIT)

        awaitDb("the last status to be stored") { checkingOf(take) == CheckingStatus.PEER_EDIT }
        settle()
        assertEquals(CheckingStatus.PEER_EDIT, checkingOf(take), "no earlier write lands after the last one")
    }

    /** Relay defect 4: a write carries the whole row as it was when the take was loaded. */
    @Test
    fun `a checking status change brings a deleted take's row back to not deleted`() {
        val chunk = openWorkbook().verse(1)
        val take = newTake(1)
        record(chunk, take)
        delete(take)
        awaitDb("the delete to be stored") { takeRow(take)?.deletedTs != null }

        setChecking(take, CheckingStatus.VERSE)

        awaitDb("the status to be stored") { checkingOf(take) == CheckingStatus.VERSE }
        assertNull(takeRow(take)?.deletedTs, "defect 4: the status write cleared the deleted flag")
    }

    /** Relay defect 3: the status chain of a take ends at its first failed write. */
    @Test
    fun `after a failed checking status save, that take saves no more and another take still does`() {
        val workbook = openWorkbook()
        val take = newTake(1)
        record(workbook.verse(1), take)
        val other = newTake(1, verse = 2)
        record(workbook.verse(2), other, verse = 2)
        every { takeDao.update(any()) } throws IOException("injected") andThenAnswer { callOriginal() }

        setChecking(take, CheckingStatus.PEER_EDIT)
        awaitDb("the failure to reach the global handler") { noStrayErrors.drain().isNotEmpty() }

        setChecking(take, CheckingStatus.VERSE)
        setChecking(other, CheckingStatus.VERSE)
        awaitDb("the other take's status to be stored") { checkingOf(other, verse = 2) == CheckingStatus.VERSE }
        settle()
        assertEquals(CheckingStatus.UNCHECKED, checkingOf(take), "defect 3: nothing is saved for this take again")
        assertEquals(CheckingStatus.VERSE, checkingInMemory(take), "memory says otherwise")
    }

    /** Relay defects 7 and 11: the failure goes to the global handler, and memory keeps the change. */
    @Test
    fun `a failed delete save is reported to nobody, stays in memory, and later saves still work`() {
        val workbook = openWorkbook()
        val take = newTake(1)
        record(workbook.verse(1), take)
        val other = newTake(1, verse = 2)
        record(workbook.verse(2), other, verse = 2)
        every { takeDao.update(any()) } throws IOException("injected") andThenAnswer { callOriginal() }

        delete(take)
        awaitDb("the failure to reach the global handler") { noStrayErrors.drain().isNotEmpty() }

        assertTrue(take.isDeleted())
        assertNull(takeRow(take)?.deletedTs, "the row was not written")
        delete(other)
        awaitDb("a later delete to be stored") { takeRow(other, verse = 2)?.deletedTs != null }
    }

    @Test
    fun `a playback rate change is stored once per distinct value`() {
        val workbook = openWorkbook()
        awaitDb("the translation to exist") { db.translationDao.fetchAll().isNotEmpty() }

        workbook.translation.updateSourceRate(1.5)
        workbook.translation.updateSourceRate(1.5)
        awaitDb("the rate to be stored") { db.translationDao.fetchAll().single().sourceRate == 1.5 }
        workbook.translation.updateTargetRate(0.75)
        awaitDb("the rate to be stored") { db.translationDao.fetchAll().single().targetRate == 0.75 }
        settle()

        verify(exactly = 2) { translationDao.update(any()) }
        assertEquals(1.5, db.translationDao.fetchAll().single().sourceRate, "the target write kept the source rate")
    }

    @Test
    fun `setting a playback rate to what it already is stores nothing`() {
        val workbook = openWorkbook()
        awaitDb("the translation to exist") { db.translationDao.fetchAll().isNotEmpty() }

        workbook.translation.updateSourceRate(1.0)
        settle()

        verify(exactly = 0) { translationDao.update(any()) }
    }

    /** Relay defect 2: only the playback rates stop being saved. Some code may rely on the rest. */
    @Test
    fun `closing a workbook stops playback rate saves and nothing else`() {
        val workbook = openWorkbook()
        val chunk = workbook.verse(1)
        val first = newTake(1)
        record(chunk, first)
        awaitDb("the translation to exist") { db.translationDao.fetchAll().isNotEmpty() }

        workbooks.closeWorkbook(workbook)

        workbook.translation.updateSourceRate(2.0)
        val second = newTake(2)
        val secondRow = record(chunk, second)
        chunk.audio.selectTake(first)
        awaitDb("a selection after close to be stored") { verseRow(1).selectedTakeFk == takeRow(first)?.id }
        setChecking(second, CheckingStatus.VERSE)
        awaitDb("a status after close to be stored") { checkingOf(second) == CheckingStatus.VERSE }
        delete(second)
        awaitDb("a delete after close to be stored") { db.takeDao.fetchById(secondRow.id).deletedTs != null }
        settle()
        assertEquals(1.0, db.translationDao.fetchAll().single().sourceRate, "the rate was not saved")
    }

    @Test
    fun `asking for the same project twice returns the same workbook until it is closed`() {
        val workbook = openWorkbook()

        assertTrue(workbook === openWorkbook())
        workbooks.closeWorkbook(workbook)
        assertTrue(workbook !== openWorkbook())
    }
}
