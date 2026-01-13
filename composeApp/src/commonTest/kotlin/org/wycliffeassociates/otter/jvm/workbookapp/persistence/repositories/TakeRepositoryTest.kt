package org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories

import io.mockk.*
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.AppDatabase
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.mapping.CollectionMapper
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.mapping.MarkerMapper
import org.bibletranslationtools.otter.common.persistence.database.daos.TakeDao
import org.bibletranslationtools.otter.common.persistence.database.daos.MarkerDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentTypeDao
import org.bibletranslationtools.otter.common.persistence.database.daos.CheckingStatusDao
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.TakeEntity
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TakeRepositoryTest {

    private val db = mockk<AppDatabase>()
    private val takeDao = mockk<TakeDao>()
    private val markerDao = mockk<MarkerDao>()
    private val contentDao = mockk<ContentDao>()
    private val contentTypeDao = mockk<ContentTypeDao>()
    private val checkingStatusDao = mockk<CheckingStatusDao>()
    private val markerMapper = mockk<MarkerMapper>()
    private val collectionMapper = mockk<CollectionMapper>()

    private lateinit var repository: TakeRepository

    @BeforeTest
    fun setup() {
        mockkStatic(Schedulers::class)
        every { Schedulers.io() } returns Schedulers.trampoline()

        every { db.takeDao } returns takeDao
        every { db.markerDao } returns markerDao
        every { db.contentDao } returns contentDao
        every { db.contentTypeDao } returns contentTypeDao
        every { db.checkingStatusDao } returns checkingStatusDao
        
        every { contentTypeDao.fetchId(any()) } returns 1
        every { contentTypeDao.fetchForId(any()) } returns org.bibletranslationtools.otter.common.data.primitives.ContentType.TEXT
        every { checkingStatusDao.fetchId(any()) } returns 1
        every { checkingStatusDao.fetchById(any()) } returns mockk(relaxed = true)
        every { markerMapper.mapToEntity(any(), any()) } returns mockk(relaxed = true)
        
        repository = TakeRepository(db, markerMapper, collectionMapper)
    }

    @Test
    fun testGetAllSuspend() = runTest {
        val entity = mockk<TakeEntity>(relaxed = true)
        val take = mockk<Take>()
        val markerEntity = mockk<org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.MarkerEntity>()
        
        every { entity.createdTs } returns LocalDate.now().toString()
        every { entity.deletedTs } returns null
        every { takeDao.fetchAll() } returns listOf(entity)
        every { markerDao.fetchByTakeId(any()) } returns listOf(markerEntity)
        every { markerMapper.mapFromEntity(markerEntity) } returns mockk()
        
        // TakeRepository creates takeMapper internally using db.checkingStatusDao
        // We need to be careful here because takeMapper is private.
        // However, it uses takeDao and buildTake (which uses markerDao/markerMapper)
        
        // Since we can't easily mock the private takeMapper, we'll let it run 
        // and ensure the dependencies it uses are mocked.
        
        val result = repository.getAllSuspend()
        assertEquals(1, result.size)
        verify { takeDao.fetchAll() }
    }

    @Test
    fun testInsertForContentSuspend() = runTest {
        val take = mockk<Take>(relaxed = true)
        val content = mockk<Content>(relaxed = true)
        val expectedId = 123
        val takeEntity = mockk<TakeEntity>()

        // Mocking the behavior inside insertForContent
        // It creates takeEntity via takeMapper (which is private but uses checkingStatusDao)
        // For simplicity, let's just mock the Dao calls
        every { takeDao.insert(any()) } returns expectedId
        every { take.markers } returns emptyList()

        val result = repository.insertForContentSuspend(take, content)
        assertEquals(expectedId, result)
        verify { takeDao.insert(any()) }
    }

    @Test
    fun testDeleteSuspend() = runTest {
        val take = mockk<Take>(relaxed = true)
        
        every { takeDao.delete(any()) } just Runs

        repository.deleteSuspend(take)
        verify { takeDao.delete(any()) }
    }

    @Test
    fun testMarkDeletedSuspend() = runTest {
        val take = mockk<Take>(relaxed = true)
        val withDeletionFlag = mockk<Take>(relaxed = true)
        
        every { take.copy(deleted = any()) } returns withDeletionFlag
        every { takeDao.update(any()) } just Runs

        repository.markDeletedSuspend(take)
        verify { takeDao.update(any()) }
    }
}
