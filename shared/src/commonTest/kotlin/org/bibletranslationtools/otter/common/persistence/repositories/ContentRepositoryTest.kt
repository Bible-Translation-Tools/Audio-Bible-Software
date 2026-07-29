package org.bibletranslationtools.otter.common.persistence.repositories

import io.mockk.*
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.IAppDatabase
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentTypeDao
import org.bibletranslationtools.otter.common.persistence.database.daos.CheckingStatusDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentDao
import org.bibletranslationtools.otter.common.persistence.database.daos.MarkerDao
import org.bibletranslationtools.otter.common.persistence.database.daos.TakeDao
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentRepositoryTest {

    private val db = mockk<IAppDatabase>()
    private val contentDao = mockk<ContentDao>()
    private val takeDao = mockk<TakeDao>()
    private val markerDao = mockk<MarkerDao>()
    private val contentTypeDao = mockk<ContentTypeDao>()
    private val checkingStatusDao = mockk<CheckingStatusDao>()

    private lateinit var repository: ContentRepository

    @BeforeTest
    fun setup() {
        mockkStatic(Schedulers::class)
        every { Schedulers.io() } returns Schedulers.trampoline()

        every { db.contentDao } returns contentDao
        every { db.takeDao } returns takeDao
        every { db.markerDao } returns markerDao
        every { db.contentTypeDao } returns contentTypeDao
        every { db.checkingStatusDao } returns checkingStatusDao
        
        every { contentTypeDao.fetchId(any()) } returns 1
        every { contentTypeDao.fetchForId(any()) } returns org.bibletranslationtools.otter.common.data.primitives.ContentType.TEXT
        every { checkingStatusDao.fetchId(any()) } returns 1
        
        repository = ContentRepository(db)
    }

    @Test
    fun testByCollectionSuspend() = runTest {
        val collection = mockk<Collection>(relaxed = true)
        val entity = mockk<ContentEntity>(relaxed = true)
        
        every { collection.id } returns 1
        every { contentDao.fetchByCollectionId(any()) } returns listOf(entity)
        every { contentDao.fetchSources(any()) } returns emptyList()
        every { entity.selectedTakeFk } returns null
        
        val result = repository.getByCollectionSuspend(collection)
        assertEquals(1, result.size)
        verify { contentDao.fetchByCollectionId(any()) }
    }

    @Test
    fun testGetByCollectionFlow() = runTest {
        val collection = mockk<Collection>(relaxed = true)
        val entity = mockk<ContentEntity>(relaxed = true)
        
        every { collection.id } returns 1
        every { contentDao.fetchByCollectionId(any()) } returns listOf(entity)
        every { contentDao.fetchSources(any()) } returns emptyList()
        every { entity.selectedTakeFk } returns null

        val flow = repository.getByCollectionFlow(collection)
        val result = flow.first()
        
        assertEquals(1, result.size)
    }

    @Test
    fun testUpdateSuspend() = runTest {
        val content = mockk<Content>(relaxed = true)
        val entity = mockk<ContentEntity>(relaxed = true)
        
        every { content.id } returns 1
        every { contentDao.fetchById(any()) } returns entity
        every { contentDao.update(any()) } just Runs

        repository.updateSuspend(content)
        verify { contentDao.update(any()) }
    }
}
