package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UnitListViewModelTest : KoinTest {

    private val workbookRepository: IWorkbookRepository = mockk()
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository = mockk()
    private val collectionRepository: ICollectionRepository = mockk()
    private val audioPlayerConnectionFactory: AudioPlayerConnectionFactory = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        startKoin {
            modules(module {
                single { workbookRepository }
                single { workbookDescriptorRepository }
                single { collectionRepository }
                single { audioPlayerConnectionFactory }
            })
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

//    @Test
//    fun `loadUnits should populate uiState with units when workbook and chapter are found`() = runTest(testDispatcher) {
//        // Given
//        val viewModel = UnitListViewModel(testDispatcher)
//        val sourceId = 1
//        val targetId = 2
//        val chapterNumber = 1
//        val sourceCollection = mockk<Collection>()
//        val targetCollection = mockk<Collection>()
//        val workbook = mockk<Workbook>()
//        val chapter = mockk<Chapter>()
//        val chunk1 = mockk<Chunk>(relaxed = true)
//        val chunk2 = mockk<Chunk>(relaxed = true)
//        val chunks = listOf(chunk1, chunk2)
//
//        every { collectionRepository.getProject(sourceId).blockingGet() } returns sourceCollection
//        every { collectionRepository.getProject(targetId).blockingGet() } returns targetCollection
//        every { workbookRepository.get(sourceCollection, targetCollection) } returns workbook
//
//        // Mock workbook structure to return our chapter
//        every { workbook.target } returns mockk {
//            every { chaptersFlow } returns flowOf(chapter)
//        }
//
//        every { chapter.sort } returns chapterNumber
//        coEvery { chapter.chunksSuspend() } returns chunks
//        every { chapter.observableFlowChunks } returns flowOf(chunks)
//
//        // When
//        viewModel.loadUnits(sourceId, targetId, chapterNumber)
//
//        // Then
//        // Allow coroutines to run
//        testDispatcher.scheduler.advanceUntilIdle()
//
//        val state = viewModel.uiState.value
//        assertEquals(workbook, state.workbook)
//        assertEquals(chapter, state.chapter)
//        assertEquals(2, state.units.size)
//        // Add more assertions as needed
//    }

//    @Test
//    fun `loadUnits should set error when workbook is not found`() = runTest(testDispatcher) {
//        // Given
//        val viewModel = UnitListViewModel(testDispatcher)
//        val sourceId = 1
//        val targetId = 2
//        val chapterNumber = 1
//        val sourceCollection = mockk<Collection>()
//        val targetCollection = mockk<Collection>()
//
//        every { collectionRepository.getProject(sourceId).blockingGet() } returns sourceCollection
//        every { collectionRepository.getProject(targetId).blockingGet() } returns targetCollection
//        every { workbookRepository.get(sourceCollection, targetCollection) } returns null
//
//        // When
//        viewModel.loadUnits(sourceId, targetId, chapterNumber)
//
//        // Then
//        testDispatcher.scheduler.advanceUntilIdle()
//
//        val state = viewModel.uiState.value
//        assertEquals("Workbook not found", state.error)
//    }
}
