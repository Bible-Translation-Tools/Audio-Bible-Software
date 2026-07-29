package org.bibletranslationtools.otter.common.persistence.repositories

import io.mockk.*
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.persistence.database.daos.CollectionDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ResourceMetadataDao
import org.bibletranslationtools.otter.common.persistence.database.daos.LanguageDao
import org.bibletranslationtools.otter.common.persistence.database.daos.WorkbookTypeDao
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.CollectionMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.LanguageMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.ResourceMetadataMapper
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionRepositoryTest {

    private val db = mockk<IAppDatabase>()
    private val directoryProvider = mockk<IDirectoryProvider>()
    private val collectionMapper = mockk<CollectionMapper>()
    private val metadataMapper = mockk<ResourceMetadataMapper>()
    private val languageMapper = mockk<LanguageMapper>()
    
    private val collectionDao = mockk<CollectionDao>()
    private val contentDao = mockk<ContentDao>()
    private val metadataDao = mockk<ResourceMetadataDao>()
    private val languageDao = mockk<LanguageDao>()
    private val workbookTypeDao = mockk<WorkbookTypeDao>()

    private lateinit var repository: CollectionRepository

    @BeforeTest
    fun setup() {
        mockkStatic(Schedulers::class)
        every { Schedulers.io() } returns Schedulers.trampoline()

        every { db.collectionDao } returns collectionDao
        every { db.contentDao } returns contentDao
        every { db.resourceMetadataDao } returns metadataDao
        every { db.languageDao } returns languageDao
        every { db.workbookTypeDao } returns workbookTypeDao

        repository = CollectionRepository(
            db,
            directoryProvider,
            collectionMapper,
            metadataMapper,
            languageMapper
        )
    }

    @Test
    fun testGetAllSuspend() = runTest {
        val entity = mockk<CollectionEntity>(relaxed = true)
        val collection = mockk<Collection>()
        
        every { collectionDao.fetchAll() } returns listOf(entity)
        // buildCollection uses multiple DAOs and mappers internally.
        // We can just spy buildCollection or mock all its internal calls.
        
        val spy = spyk(repository, recordPrivateCalls = true)
        // Need to use internal call mocking or mock everything buildCollection calls.
        // Let's mock the mapper call instead.
        
        every { collectionMapper.mapFromEntity(entity, any()) } returns collection
        every { metadataDao.fetchById(any()) } returns mockk(relaxed = true)
        every { metadataMapper.mapFromEntity(any(), any()) } returns mockk(relaxed = true)
        every { languageDao.fetchById(any()) } returns mockk(relaxed = true)
        every { languageMapper.mapFromEntity(any()) } returns mockk(relaxed = true)

        val result = repository.getAllSuspend()
        assertEquals(1, result.size)
        verify { collectionDao.fetchAll() }
    }

    @Test
    fun testInsertSuspend() = runTest {
        val collection = mockk<Collection>()
        val entity = mockk<CollectionEntity>()
        val expectedId = 1
        
        every { collectionMapper.mapToEntity(collection) } returns entity
        every { collectionDao.insert(entity) } returns expectedId
        
        val result = repository.insertSuspend(collection)
        assertEquals(expectedId, result)
        verify { collectionDao.insert(entity) }
    }
}
