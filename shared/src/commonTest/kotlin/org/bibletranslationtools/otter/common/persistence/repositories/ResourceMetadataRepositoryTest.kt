package org.bibletranslationtools.otter.common.persistence.repositories

import io.mockk.*
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.persistence.database.dao.ResourceMetadataDao
import org.bibletranslationtools.otter.common.persistence.database.dao.LanguageDao
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.LanguageMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.ResourceMetadataMapper
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceMetadataRepositoryTest {

    private val db = mockk<DaoProvider>()
    private val resourceMetadataDao = mockk<ResourceMetadataDao>()
    private val languageDao = mockk<LanguageDao>()
    private val metadataMapper = mockk<ResourceMetadataMapper>()
    private val languageMapper = mockk<LanguageMapper>()

    private lateinit var repository: ResourceMetadataRepository

    @BeforeTest
    fun setup() {
        mockkStatic(Schedulers::class)
        every { Schedulers.io() } returns Schedulers.trampoline()

        every { db.resourceMetadataDao } returns resourceMetadataDao
        every { db.languageDao } returns languageDao

        repository = ResourceMetadataRepository(db, metadataMapper, languageMapper)
    }

    @Test
    fun testExistsSuspend() = runTest {
        val metadata = mockk<ResourceMetadata>(relaxed = true)
        val languageEntity = mockk<LanguageEntity>(relaxed = true)
        
        every { metadata.language.slug } returns "en"
        every { languageDao.fetchBySlug("en") } returns languageEntity
        every { languageEntity.id } returns 1
        every { resourceMetadataDao.exists(1, any(), any(), any()) } returns true
        
        val result = repository.existsSuspend(metadata)
        assertTrue(result)
        verify { resourceMetadataDao.exists(1, any(), any(), any()) }
    }

    @Test
    fun testGetAllSuspend() = runTest {
        val entity = mockk<ResourceMetadataEntity>(relaxed = true)
        val languageEntity = mockk<LanguageEntity>(relaxed = true)
        val language = mockk<Language>()
        val metadata = mockk<ResourceMetadata>()
        
        every { resourceMetadataDao.fetchAll() } returns listOf(entity)
        every { languageDao.fetchById(any()) } returns languageEntity
        every { languageMapper.mapFromEntity(languageEntity) } returns language
        every { metadataMapper.mapFromEntity(entity, language) } returns metadata
        
        val result = repository.getAllSuspend()
        assertEquals(1, result.size)
    }

    @Test
    fun testInsertSuspend() = runTest {
        val metadata = mockk<ResourceMetadata>()
        val entity = mockk<ResourceMetadataEntity>()
        val expectedId = 123
        
        every { metadataMapper.mapToEntity(metadata) } returns entity
        every { resourceMetadataDao.insert(entity) } returns expectedId
        
        val result = repository.insertSuspend(metadata)
        assertEquals(expectedId, result)
        verify { resourceMetadataDao.insert(entity) }
    }
}
