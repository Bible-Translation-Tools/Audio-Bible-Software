package org.bibletranslationtools.otter.common.persistence.repositories

import io.mockk.*
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.IAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.persistence.database.daos.WorkbookDescriptorDao
import org.bibletranslationtools.otter.common.persistence.database.daos.WorkbookTypeDao
import org.bibletranslationtools.otter.common.persistence.entities.WorkbookDescriptorEntity
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkbookDescriptorRepositoryTest {

    private val db = mockk< IAppDatabase>()
    private val collectionRepository = mockk<ICollectionRepository>()
    private val contentRepository = mockk<IContentRepository>()
    private val workbookRepository = mockk<IWorkbookRepository>()
    
    private val workbookDescriptorDao = mockk<WorkbookDescriptorDao>()
    private val workbookTypeDao = mockk<WorkbookTypeDao>()

    private lateinit var repository: WorkbookDescriptorRepository

    @BeforeTest
    fun setup() {
        mockkStatic(Schedulers::class)
        every { Schedulers.io() } returns Schedulers.trampoline()

        every { db.workbookDescriptorDao } returns workbookDescriptorDao
        every { db.workbookTypeDao } returns workbookTypeDao

        repository = WorkbookDescriptorRepository(
            db,
            collectionRepository,
            contentRepository,
            workbookRepository
        )
    }

    @Test
    fun testGetAllSuspend() = runTest {
        val entity = mockk<WorkbookDescriptorEntity>(relaxed = true)

        every { workbookDescriptorDao.fetchAll() } returns listOf(entity)
        // getAll now batch-resolves the referenced collections up front.
        val collection = mockk<Collection>(relaxed = true)
        every { collectionRepository.getProjects(any()) } returns
            Single.just(mapOf(entity.sourceFk to collection, entity.targetFk to collection))

        val spy = spyk(repository, recordPrivateCalls = true)
        val descriptor = mockk<WorkbookDescriptor>()
        every {
            spy["buildWorkbookDescriptor"](
                any<WorkbookDescriptorEntity>(),
                any<Collection>(),
                any<Collection>(),
                any<Map<String, Boolean>>(),
                any<MutableMap<Int, ProjectMode>>()
            )
        } returns descriptor

        // computeSourceAudio=false skips the resource-container open (the relaxed mock has no real
        // RC to load); the mapping/fetch behavior under test is identical.
        val result = spy.getAllSuspend(computeSourceAudio = false)
        assertEquals(1, result.size)
        verify { workbookDescriptorDao.fetchAll() }
    }

    @Test
    fun testDeleteSuspend() = runTest {
        val descriptor = mockk<WorkbookDescriptor>(relaxed = true)
        
        every { workbookDescriptorDao.delete(any<WorkbookDescriptorEntity>()) } just Runs
        every { workbookTypeDao.fetchId(any()) } returns 1

        repository.deleteSuspend(listOf(descriptor))
        verify { workbookDescriptorDao.delete(any<WorkbookDescriptorEntity>()) }
    }
}
