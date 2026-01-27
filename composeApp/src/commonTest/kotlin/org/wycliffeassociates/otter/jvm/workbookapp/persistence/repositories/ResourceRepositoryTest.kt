package org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories

import io.mockk.*
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.AppDatabase
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentTypeDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ResourceLinkDao
import org.bibletranslationtools.otter.common.persistence.database.daos.SubtreeHasResourceDao
import org.bibletranslationtools.otter.common.persistence.database.daos.*
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.ContentEntity
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.ResourceLinkEntity
import org.bibletranslationtools.otter.common.persistence.database.daos.CheckingStatusDao
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceRepositoryTest {

    private val db = mockk<AppDatabase>()
    private val contentDao = mockk<ContentDao>()
    private val contentTypeDao = mockk<ContentTypeDao>()
    private val collectionDao = mockk<CollectionDao>()
    private val takeDao = mockk<TakeDao>()
    private val markerDao = mockk<MarkerDao>()
    private val resourceLinkDao = mockk<ResourceLinkDao>()
    private val subtreeHasResourceDao = mockk<SubtreeHasResourceDao>()
    private val languageDao = mockk<LanguageDao>()
    private val checkingStatusDao = mockk<CheckingStatusDao>()

    private lateinit var repository: ResourceRepository

    @BeforeTest
    fun setup() {
        mockkStatic(Schedulers::class)
        every { Schedulers.io() } returns Schedulers.trampoline()

        every { db.contentDao } returns contentDao
        every { db.contentTypeDao } returns contentTypeDao
        every { db.collectionDao } returns collectionDao
        every { db.takeDao } returns takeDao
        every { db.markerDao } returns markerDao
        every { db.resourceLinkDao } returns resourceLinkDao
        every { db.subtreeHasResourceDao } returns subtreeHasResourceDao
        every { db.languageDao } returns languageDao
        every { db.checkingStatusDao } returns checkingStatusDao
        
        every { contentTypeDao.fetchId(any()) } returns 1
        every { contentTypeDao.fetchForId(any()) } returns org.bibletranslationtools.otter.common.data.primitives.ContentType.TEXT
        every { checkingStatusDao.fetchId(any()) } returns 1
        
        repository = ResourceRepository(db)
    }

    @Test
    fun testGetAllSuspend() = runTest {
        val entity = mockk<ContentEntity>(relaxed = true)
        
        every { contentDao.fetchAll() } returns listOf(entity)
        every { contentDao.fetchSources(any()) } returns emptyList()
        every { entity.selectedTakeFk } returns null
        
        val result = repository.getAllSuspend()
        assertEquals(1, result.size)
        verify { contentDao.fetchAll() }
    }

    @Test
    fun testLinkToContentSuspend() = runTest {
        val resource = mockk<Content>(relaxed = true)
        val content = mockk<Content>(relaxed = true)
        val dublinCoreFk = 456

        every { resource.id } returns 1
        every { content.id } returns 2
        every { resourceLinkDao.insertNoReturn(any()) } just Runs

        repository.linkToContentSuspend(resource, content, dublinCoreFk)
        verify { resourceLinkDao.insertNoReturn(any()) }
    }

    @Test
    fun testDeleteSuspend() = runTest {
        val content = mockk<Content>(relaxed = true)
        
        every { contentDao.delete(any()) } just Runs

        repository.deleteSuspend(content)
        verify { contentDao.delete(any()) }
    }
}
