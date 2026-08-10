package org.bibletranslationtools.orature.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.orature.services.OratureTakeAudio
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Book
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.narration.LoadChapterSourceText
import org.bibletranslationtools.otter.common.domain.project.InitializeProjectFiles
import org.bibletranslationtools.otter.common.domain.project.OpenWorkbook
import org.bibletranslationtools.orature.di.oratureViewModelModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OratureNarrationViewModelTest : KoinTest {

    private val descriptorRepo: IWorkbookDescriptorRepository = mockk(relaxed = true)
    private val workbookRepo: IWorkbookRepository = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    private val descriptorId = 7

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        startKoin {
            modules(
                // Compose the REAL app-scoped module so the graph under test is the production
                // one. Hand-listing app singles here is what let OratureProjectDeletion go
                // unbound: Koin's `by inject()` is lazy, so the omission surfaced only as
                // create-path tests timing out. Stub ONLY the backend ports below.
                oratureViewModelModule,
                module {
                    single { descriptorRepo }
                    single { workbookRepo }
                    // The :shared use cases the VM injects, built over the stubbed ports above.
                    // In the app these come from implicitCommonModule (asserted by
                    // SharedGraphWiringTest); this module is not composed here, so they are
                    // supplied explicitly rather than left to fail lazily at first use.
                    single { OpenWorkbook(descriptorRepo, workbookRepo, testDispatcher) }
                    single { LoadChapterSourceText(testDispatcher) }
                    single { InitializeProjectFiles(testDispatcher) }
                }
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun chapter(sort: Int, completed: Boolean = false): Chapter = mockk {
        every { this@mockk.sort } returns sort
        every { title } returns sort.toString()
        every { hasSelectedAudio() } returns completed
    }

    /** Wire the descriptor→workbook→chapters chain used by the VM's load(). */
    private fun stubWorkbook(chapters: List<Chapter>, mode: ProjectMode = ProjectMode.NARRATION) {
        val sourceCol: Collection = mockk(relaxed = true)
        val targetCol: Collection = mockk(relaxed = true)
        val descriptor: WorkbookDescriptor = mockk {
            every { sourceCollection } returns sourceCol
            every { targetCollection } returns targetCol
            every { this@mockk.mode } returns mode
        }
        val targetBook: Book = mockk {
            every { title } returns "Matthew"
            every { slug } returns "mat"
            every { this@mockk.chapters } returns Observable.fromIterable(chapters)
        }
        val workbook: Workbook = mockk(relaxed = true) {
            every { target } returns targetBook
        }
        coEvery { descriptorRepo.getByIdSuspend(descriptorId) } returns descriptor
        every { workbookRepo.get(sourceCol, targetCol) } returns workbook
    }

    private fun newVm() = OratureNarrationViewModel(descriptorId, testDispatcher)

    /**
     * Drains the scheduler and returns the settled state. No timeout: load() runs on
     * [testDispatcher], so advanceUntilIdle() is a definite "all pending work has run" rather than
     * a guess about how long real threads need.
     */
    private fun TestScope.awaitLoaded(vm: OratureNarrationViewModel): OratureNarrationUiState {
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading, "load() did not settle")
        return vm.uiState.value
    }

    /**
     * Every test body runs on [testDispatcher], which is also what the ViewModel and the three
     * :shared use cases dispatch their IO to. That makes the whole load path single-threaded and
     * `advanceUntilIdle()` exact.
     *
     * This replaces a `runReal` helper that called `Dispatchers.resetMain()` and raced the Swing
     * EDT against a 10-second `withTimeout`. It failed about one full-suite run in three, and when
     * it did the assertion read `expected:<2> but was:<null>` — load() had taken the error path and
     * `awaitLoaded` returned it happily, because a state with an error is also a state that is no
     * longer loading.
     */
    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(testDispatcher) { block() }

    // ---- load ---------------------------------------------------------------------------

    @Test
    fun `loads workbook and builds chapter grid with first chapter active`() = runVmTest {
        stubWorkbook(listOf(chapter(1), chapter(2, completed = true), chapter(3)))

        val state = awaitLoaded(newVm())

        assertNull(state.error)
        assertEquals("Matthew", state.bookTitle)
        assertEquals(3, state.chapters.size)
        assertEquals(1, state.activeChapterSort)
        assertTrue(state.chapters.first { it.sort == 1 }.selected)
        assertTrue(state.chapters.first { it.sort == 2 }.completed)
        assertFalse(state.chapters.first { it.sort == 1 }.completed)
        assertFalse(state.hasPreviousChapter)
        assertTrue(state.hasNextChapter)
    }

    @Test
    fun `selecting a chapter updates active selection and neighbor availability`() = runVmTest {
        stubWorkbook(listOf(chapter(1), chapter(2), chapter(3)))
        val vm = newVm()
        awaitLoaded(vm)

        vm.selectChapter(3)
        advanceUntilIdle()
        val state = vm.uiState.value

        assertEquals(3, state.activeChapterSort)
        assertEquals("3", state.activeChapterTitle)
        assertTrue(state.chapters.first { it.sort == 3 }.selected)
        assertFalse(state.chapters.first { it.sort == 1 }.selected)
        assertTrue(state.hasPreviousChapter)
        assertFalse(state.hasNextChapter)
    }

    @Test
    fun `next and previous step through chapters within bounds`() = runVmTest {
        stubWorkbook(listOf(chapter(1), chapter(2), chapter(3)))
        val vm = newVm()
        awaitLoaded(vm)

        vm.selectNextChapter()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.activeChapterSort)

        vm.selectNextChapter()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.activeChapterSort)

        // At the last chapter, next is a no-op.
        vm.selectNextChapter()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.activeChapterSort)

        vm.selectPreviousChapter()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.activeChapterSort)
    }

    @Test
    fun `remembers the last-viewed chapter when the workbook is reopened`() = runVmTest {
        stubWorkbook(listOf(chapter(1), chapter(2), chapter(3)))

        val first = newVm()
        awaitLoaded(first)
        first.selectChapter(2)
        advanceUntilIdle()

        // A second VM over the same descriptor shares the datastore's recent-chapter map.
        val second = awaitLoaded(newVm())
        assertEquals(2, second.activeChapterSort)
    }

    /**
     * The message assertion matters more than it looks: `error != null` alone passes for ANY
     * failure during load, including an unresolvable Koin dependency. When OpenWorkbook was
     * extracted and not yet bound in this module, the four tests above went red while this one
     * stayed green on a "No definition found for type OpenWorkbook" error — it was reporting the
     * happy-path-is-broken case as a pass. Pinning the message keeps it honest about *which*
     * failure it is describing.
     */
    @Test
    fun `error state when the descriptor is missing`() = runVmTest {
        coEvery { descriptorRepo.getByIdSuspend(descriptorId) } returns null

        val state = awaitLoaded(newVm())

        assertEquals(0, state.chapters.size)
        val error = state.error
        assertTrue(error != null, "a missing descriptor should surface an error")
        assertTrue(
            "descriptor" in error && "$descriptorId" in error,
            "the error should name the missing descriptor, was: $error"
        )
    }

    // ---- take → audio adapter -----------------------------------------------------------

    @Test
    fun `adapter yields no timeline when nothing is selected`() {
        val audio: AssociatedAudio = mockk { every { getSelectedTake() } returns null }
        assertNull(OratureTakeAudio.timelineForSelected(audio))
    }

    @Test
    fun `adapter yields no timeline for a soft-deleted take`() {
        val deleted: Take = mockk { every { isDeleted() } returns true }
        val audio: AssociatedAudio = mockk { every { getSelectedTake() } returns deleted }
        assertNull(OratureTakeAudio.timelineForSelected(audio))
    }
}
