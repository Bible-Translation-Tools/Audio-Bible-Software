package org.bibletranslationtools.orature.ui.viewmodels

import io.mockk.coEvery
import io.mockk.mockk
import io.reactivex.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.primitives.Anthology
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OratureHomeViewModelTest : KoinTest {

    private val workbookDescriptorRepository: IWorkbookDescriptorRepository = mockk()
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        startKoin {
            modules(
                module {
                    single { workbookDescriptorRepository }
                }
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    private fun fakeLanguage(slug: String, name: String) = Language(
        slug = slug,
        name = name,
        anglicizedName = name,
        direction = "ltr",
        isGateway = true,
        region = "US"
    )

    private fun fakeCollection(
        slug: String,
        title: String,
        language: Language,
        resourceSlug: String,
        modifiedTs: LocalDateTime? = null
    ) = Collection(
        sort = 1,
        slug = slug,
        labelKey = title,
        titleKey = title,
        modifiedTs = modifiedTs,
        resourceContainer = ResourceMetadata(
            conformsTo = "rc0.2",
            creator = "WA",
            description = "Test",
            format = "audio/wav",
            identifier = resourceSlug,
            issued = LocalDate.now(),
            language = language,
            modified = LocalDate.now(),
            publisher = "WA",
            subject = "Bible",
            type = ContainerType.Bundle,
            title = title,
            version = "1",
            license = "CC BY-SA 4.0",
            path = File("")
        )
    )

    private fun fakeDescriptor(
        id: Int,
        slug: String,
        title: String,
        sourceLanguage: Language,
        targetLanguage: Language,
        progress: Double,
        mode: ProjectMode = ProjectMode.TRANSLATION,
        resourceSlug: String = "ulb",
        modifiedTs: LocalDateTime? = null
    ) = WorkbookDescriptor(
        id = id,
        sourceCollection = fakeCollection(slug, title, sourceLanguage, resourceSlug),
        targetCollection = fakeCollection(slug, title, targetLanguage, resourceSlug, modifiedTs),
        mode = mode,
        progress = Single.just(progress)
    )

    @Test
    fun `groups descriptors by source, target, resource slug, and mode`() = runTest(testDispatcher) {
        val english = fakeLanguage("eng", "English")
        val spanish = fakeLanguage("spa", "Spanish")
        val descriptors = listOf(
            fakeDescriptor(1, "gen", "Genesis", english, spanish, 0.5, resourceSlug = "ulb"),
            fakeDescriptor(2, "exo", "Exodus", english, spanish, 0.0, resourceSlug = "ulb"),
            // Different resource slug -> separate group even with same languages/mode.
            fakeDescriptor(3, "mat", "Matthew", english, spanish, 0.2, resourceSlug = "udb")
        )
        coEvery { workbookDescriptorRepository.getAllSuspend() } returns descriptors

        val viewModel = OratureHomeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.projectGroups.size)

        val ulbGroup = state.projectGroups.first { it.resourceSlug == "ulb" }
        assertEquals(2, ulbGroup.books.size)
        assertEquals(setOf("gen", "exo"), ulbGroup.books.map { it.slug }.toSet())

        val udbGroup = state.projectGroups.first { it.resourceSlug == "udb" }
        assertEquals(1, udbGroup.books.size)
        assertEquals("mat", udbGroup.books.first().slug)
    }

    @Test
    fun `selects the first group by default and exposes its books`() = runTest(testDispatcher) {
        val english = fakeLanguage("eng", "English")
        val spanish = fakeLanguage("spa", "Spanish")
        val descriptors = listOf(
            fakeDescriptor(1, "gen", "Genesis", english, spanish, 0.5, resourceSlug = "ulb")
        )
        coEvery { workbookDescriptorRepository.getAllSuspend() } returns descriptors

        val viewModel = OratureHomeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.selectedGroupKey)
        assertEquals(state.projectGroups.first().key, state.selectedGroupKey)
        assertEquals(1, state.visibleBooks.size)
        assertEquals("Genesis", state.visibleBooks.first().title)
    }

    @Test
    fun `most recently modified group is selected first`() = runTest(testDispatcher) {
        val english = fakeLanguage("eng", "English")
        val spanish = fakeLanguage("spa", "Spanish")
        val older = LocalDateTime.now().minusDays(5)
        val newer = LocalDateTime.now()
        val descriptors = listOf(
            fakeDescriptor(1, "gen", "Genesis", english, spanish, 0.5, resourceSlug = "ulb", modifiedTs = older),
            fakeDescriptor(2, "mat", "Matthew", english, spanish, 0.2, resourceSlug = "udb", modifiedTs = newer)
        )
        coEvery { workbookDescriptorRepository.getAllSuspend() } returns descriptors

        val viewModel = OratureHomeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("udb", state.projectGroups.first().resourceSlug)
        assertEquals("udb", state.selectedGroup?.resourceSlug)
    }

    @Test
    fun `book search filters the selected group's books by title or slug`() = runTest(testDispatcher) {
        val english = fakeLanguage("eng", "English")
        val spanish = fakeLanguage("spa", "Spanish")
        val descriptors = listOf(
            fakeDescriptor(1, "gen", "Genesis", english, spanish, 0.5, resourceSlug = "ulb"),
            fakeDescriptor(2, "exo", "Exodus", english, spanish, 0.0, resourceSlug = "ulb")
        )
        coEvery { workbookDescriptorRepository.getAllSuspend() } returns descriptors

        val viewModel = OratureHomeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onBookSearchQueryChange("exo")
        assertEquals(1, viewModel.uiState.value.visibleBooks.size)
        assertEquals("Exodus", viewModel.uiState.value.visibleBooks.first().title)

        viewModel.onBookSearchQueryChange("GEN")
        assertEquals(1, viewModel.uiState.value.visibleBooks.size)
        assertEquals("gen", viewModel.uiState.value.visibleBooks.first().slug)

        viewModel.onBookSearchQueryChange("")
        assertEquals(2, viewModel.uiState.value.visibleBooks.size)
    }

    @Test
    fun `isEmptyGroups is true when repository returns no projects`() = runTest(testDispatcher) {
        coEvery { workbookDescriptorRepository.getAllSuspend() } returns emptyList()

        val viewModel = OratureHomeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.isEmptyGroups)
        assertTrue(state.projectGroups.isEmpty())
    }

    @Test
    fun `maps descriptor progress and anthology into the book UI model`() = runTest(testDispatcher) {
        val english = fakeLanguage("eng", "English")
        val descriptors = listOf(
            fakeDescriptor(1, "gen", "Genesis", english, english, 0.75, resourceSlug = "ulb")
        )
        coEvery { workbookDescriptorRepository.getAllSuspend() } returns descriptors

        val viewModel = OratureHomeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        val book = state.visibleBooks.first()
        assertEquals(0.75, book.progress)
        assertEquals(Anthology.OLD_TESTAMENT, book.anthology)
    }

    @Test
    fun `selecting a different group resets the book search query`() = runTest(testDispatcher) {
        val english = fakeLanguage("eng", "English")
        val spanish = fakeLanguage("spa", "Spanish")
        val descriptors = listOf(
            fakeDescriptor(1, "gen", "Genesis", english, spanish, 0.5, resourceSlug = "ulb"),
            fakeDescriptor(2, "mat", "Matthew", english, spanish, 0.2, resourceSlug = "udb")
        )
        coEvery { workbookDescriptorRepository.getAllSuspend() } returns descriptors

        val viewModel = OratureHomeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onBookSearchQueryChange("gen")
        val otherGroup = viewModel.uiState.value.projectGroups.first { it.resourceSlug == "udb" }
        viewModel.onSelectProjectGroup(otherGroup.key)

        val state = viewModel.uiState.value
        assertEquals("", state.bookSearchQuery)
        assertEquals(otherGroup.key, state.selectedGroupKey)
    }
}
