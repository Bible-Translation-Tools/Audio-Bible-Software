package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.AudioState
import org.bibletranslationtools.otter.common.data.workbook.Book
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.ChapterState
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.ChunkId
import org.bibletranslationtools.otter.common.data.workbook.DraftId
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeId
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookStore
import org.bibletranslationtools.otter.common.data.workbook.WorkbookWriter
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.shared.preferences.ActiveNavState
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.err_chapter_not_found
import org.bibletranslationtools.shared.resources.err_no_active_chapter
import org.bibletranslationtools.shared.resources.err_project_not_found
import org.jetbrains.compose.resources.getString
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.io.File
import java.time.LocalDate

/**
 * Covers [UnitListViewModel.loadUnits] — how navigation state becomes the verse list — plus the
 * take-browsing rules that list carries.
 *
 * This file previously held two commented-out tests and no live ones, so `:app-recorder` looked
 * like it had four test files while `UnitListViewModel` had no coverage at all. They could not
 * simply be uncommented: they called `loadUnits(sourceId, targetId, chapterNumber)` and
 * `collectionRepository.getProject(id).blockingGet()`, both of which are gone — the screen's
 * target now comes from [IAppPreferences.navState] and the repository call is suspending. The two
 * scenarios they named (populate on success, error when the project is missing) are kept below.
 *
 * Chapters and chunks are mocks; each chunk's `AssociatedAudio` and its takes are real, saving to a
 * mocked [WorkbookStore]. Every assertion about take counts, selection and browse indices is a
 * statement about the audio's behaviour, and stubbing `getAllTakes()`/`getSelectedTake()` would
 * assert only that the stubs were returned.
 */
@OptIn(ExperimentalCoroutinesApi::class)
/**
 * desktopTest, not commonTest: the failure-path tests resolve a localized message with Compose's
 * `getString(Res.string.*)`, both in the ViewModel and in the assertion. That reads the device
 * locale via `Resources.getSystem()`, which a plain Android unit test (no Robolectric) leaves
 * unmocked — so on `testDebugUnitTest` these three throw "Method getSystem in ... not mocked".
 * The ViewModel is commonMain, so the JVM run here exercises the same code; moving the whole class
 * keeps it cohesive rather than splitting three cases out. (Distinct reason from
 * OraturePluginStoreSelectionTest, which is desktop-only because the feature itself is.)
 */
class UnitListViewModelTest : KoinTest {

    private val workbookRepository: IWorkbookRepository = mockk(relaxed = true)
    private val collectionRepository: ICollectionRepository = mockk()
    private val appPreferences: IAppPreferences = mockk()
    private val audioPlayerConnectionFactory: AudioPlayerConnectionFactory = mockk(relaxed = true)

    /** Where the chunks' audio saves. Relaxed: a save succeeds unless a test says otherwise. */
    private val store: WorkbookStore = mockk(relaxed = true)
    private val writer = WorkbookWriter()
    private var nextId = 1

    private val testDispatcher = StandardTestDispatcher()

    private val sourceId = 11
    private val targetId = 22
    private val chapterSort = 3

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        startKoin {
            modules(
                module {
                    single { workbookRepository }
                    single { collectionRepository }
                    single { appPreferences }
                    single { audioPlayerConnectionFactory }
                }
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private fun take(number: Int, deleted: Boolean = false) = Take(
        name = "take$number.wav",
        // Never opened: the duration probe in loadUnits swallows the read failure by design.
        file = File("take$number.wav"),
        number = number,
        format = MimeType.WAV,
        createdTimestamp = LocalDate.of(2026, 1, number.coerceIn(1, 28)),
        deletedOn = if (deleted) LocalDate.now() else null,
        id = TakeId(nextId++)
    )

    /** A real [AssociatedAudio] holding [takes], [selected] selected, saving to [store]. */
    private fun audio(takes: List<Take> = emptyList(), selected: Take? = null) =
        AssociatedAudio(ChunkId(nextId++), AudioState(takes, selected?.id), store, writer)

    private fun chunk(
        sort: Int,
        contentType: ContentType = ContentType.TEXT,
        audio: AssociatedAudio = audio()
    ): Chunk = mockk {
        every { this@mockk.sort } returns sort
        every { this@mockk.contentType } returns contentType
        every { this@mockk.audio } returns audio
    }

    /** A chapter whose active layout holds [chunks]; [layout] changes it, as a rechunk would. */
    private class ChapterStub(val chapter: Chapter, val layout: MutableStateFlow<ChapterState>)

    private fun chapter(sort: Int, vararg chunks: Chunk): ChapterStub {
        val layout = MutableStateFlow(ChapterState(DraftId(1), chunks.toList(), loaded = true))
        val chapter: Chapter = mockk {
            every { this@mockk.sort } returns sort
            every { state } returns layout
            coEvery { loadedState() } answers { layout.value }
        }
        return ChapterStub(chapter, layout)
    }

    /**
     * Wires navState → collections → workbook → target book chapters, the chain loadUnits walks.
     * [nav] defaults to a fully-populated position.
     */
    private fun stubNavigation(
        vararg chapters: ChapterStub,
        nav: ActiveNavState = ActiveNavState(sourceId, targetId, chapterSort, unitSort = -1),
        sourceCollection: Collection? = mockk(),
        targetCollection: Collection? = mockk()
    ): Workbook {
        every { appPreferences.navState } returns flowOf(nav)
        coEvery { collectionRepository.getProjectSuspend(sourceId) } returns sourceCollection
        coEvery { collectionRepository.getProjectSuspend(targetId) } returns targetCollection

        val targetBook: Book = mockk { coEvery { chapters() } returns chapters.map { it.chapter } }
        val workbook: Workbook = mockk(relaxed = true) { every { target } returns targetBook }
        if (sourceCollection != null && targetCollection != null) {
            every { workbookRepository.get(sourceCollection, targetCollection) } returns workbook
        }
        return workbook
    }

    private fun UnitListViewModel.unit(sort: Int): Chunk = uiState.value.units.single { it.unit.sort == sort }.unit

    private fun UnitListViewModel.loadedUnits(): List<Int> = uiState.value.units.map { it.unit.sort }

    // ── loadUnits: success ───────────────────────────────────────────────────────────────

    @Test
    fun `loadUnits populates units from the active chapter`() = runTest(testDispatcher) {
        val workbook = stubNavigation(chapter(chapterSort, chunk(1), chunk(2)))
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertSame(workbook, state.workbook)
        assertEquals(chapterSort, state.chapter?.sort)
        assertEquals(listOf(1, 2), viewModel.loadedUnits())
        assertFalse(state.isLoading, "loading must clear once the chunks arrive")
        assertNull(state.error)
    }

    /**
     * The chapter's own compiled take lives on `chapter.audio` and belongs to the chapter list;
     * this screen is exclusively per-verse, so the META chunk must not become a row.
     */
    @Test
    fun `loadUnits drops the chapter meta chunk`() = runTest(testDispatcher) {
        stubNavigation(chapter(chapterSort, chunk(1, ContentType.META), chunk(2)))
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        assertEquals(listOf(2), viewModel.loadedUnits(), "only TEXT chunks are verses")
    }

    @Test
    fun `loadUnits counts only takes that are not deleted`() = runTest(testDispatcher) {
        stubNavigation(chapter(chapterSort, chunk(1, audio = audio(listOf(take(1), take(2, deleted = true))))))
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.units.single().takes)
    }

    @Test
    fun `loadUnits reports each unit's selected take`() = runTest(testDispatcher) {
        val second = take(2)
        stubNavigation(chapter(chapterSort, chunk(1, audio = audio(listOf(take(1), second), selected = second))))
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        val unit = viewModel.uiState.value.units.single()
        assertEquals("take2.wav", unit.selectedTake?.name)
        assertEquals(unit.unit.audio.getSelectedTake(), unit.selectedTake)
        assertTrue(unit.hasContent)
    }

    /**
     * The browse index starts on whatever take is selected, not at 0 — otherwise reopening the
     * screen shows take 1 while the checkmark sits on take 3.
     */
    @Test
    fun `loadUnits points the browse index at the selected take`() = runTest(testDispatcher) {
        val third = take(3)
        stubNavigation(chapter(chapterSort, chunk(7, audio = audio(listOf(take(1), take(2), third), selected = third))))
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        assertEquals(
            mapOf(7 to 2),
            viewModel.uiState.value.currentTakeIndices,
            "index 2 is the third take, ordered by take number"
        )
    }

    /**
     * loadUnits keeps following the chapter for the ViewModel's lifetime, so re-entering the screen
     * must not start a second load — that leaked a collection and flashed the spinner.
     */
    @Test
    fun `loadUnits is a no-op while a load is already in flight`() = runTest(testDispatcher) {
        val stub = chapter(chapterSort, chunk(1))
        stubNavigation(stub)
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()
        viewModel.loadUnits()
        advanceUntilIdle()
        // The first load is still following the chapter: a new layout shows up in the list.
        stub.layout.value = ChapterState(DraftId(2), listOf(chunk(1), chunk(2)), loaded = true)
        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.loadedUnits())
        io.mockk.verify(exactly = 1) { workbookRepository.get(any(), any()) }
    }

    // ── loadUnits: failure ───────────────────────────────────────────────────────────────

    /**
     * Each failure test asserts the *specific* localized message, not merely that `error` is
     * non-null. Every one of these branches ends in `_uiState.update { error = ... }`, and so does
     * the outer `catch`, so a non-null check passes just as happily when something unrelated threw
     * — which is exactly the case worth catching here.
     */
    @Test
    fun `loadUnits reports an error when there is no active chapter`() = runTest(testDispatcher) {
        every { appPreferences.navState } returns flowOf(ActiveNavState())
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(getString(Res.string.err_no_active_chapter), state.error)
        assertFalse(state.isLoading)
        assertTrue(state.units.isEmpty())
    }

    /** The scenario the old commented-out `loadUnits should set error when workbook is not found`. */
    @Test
    fun `loadUnits reports an error when the project is not found`() = runTest(testDispatcher) {
        stubNavigation(targetCollection = null)
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(getString(Res.string.err_project_not_found), state.error)
        assertFalse(state.isLoading)
        assertNull(state.workbook)
    }

    /**
     * The workbook is still published when the chapter is missing: the screen's header binds to it,
     * so dropping it would blank the header as well as the list.
     */
    @Test
    fun `loadUnits keeps the workbook when the chapter sort matches nothing`() =
        runTest(testDispatcher) {
            val workbook = stubNavigation(chapter(99))
            val viewModel = UnitListViewModel(testDispatcher)

            viewModel.loadUnits()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(getString(Res.string.err_chapter_not_found), state.error)
            assertSame(workbook, state.workbook)
            assertNull(state.chapter)
            assertFalse(state.isLoading)
        }

    // ── take browsing ────────────────────────────────────────────────────────────────────

    @Test
    fun `cycleTake wraps around in both directions`() = runTest(testDispatcher) {
        stubNavigation(chapter(chapterSort, chunk(5, audio = audio(listOf(take(1), take(2))))))
        val viewModel = UnitListViewModel(testDispatcher)
        viewModel.loadUnits()
        advanceUntilIdle()
        val unit = viewModel.unit(5)

        viewModel.cycleTake(unit, direction = 1)
        assertEquals(1, viewModel.uiState.value.currentTakeIndices[5])

        viewModel.cycleTake(unit, direction = 1)
        assertEquals(0, viewModel.uiState.value.currentTakeIndices[5], "wraps past the last take")

        viewModel.cycleTake(unit, direction = -1)
        assertEquals(1, viewModel.uiState.value.currentTakeIndices[5], "wraps below the first take")
    }

    @Test
    fun `cycleTake does nothing when the unit has no takes`() = runTest(testDispatcher) {
        stubNavigation(chapter(chapterSort, chunk(5)))
        val viewModel = UnitListViewModel(testDispatcher)
        viewModel.loadUnits()
        advanceUntilIdle()

        viewModel.cycleTake(viewModel.unit(5), direction = 1)

        assertEquals(0, viewModel.uiState.value.currentTakeIndices[5])
    }

    /**
     * Selecting saves the new selection and publishes it into ui state — without the republish
     * nothing in the state object changes, so Compose never recomposes and the checkmark appears
     * not to move.
     */
    @Test
    fun `selectCurrentTake publishes the browsed take as selected`() = runTest(testDispatcher) {
        val second = take(2)
        stubNavigation(chapter(chapterSort, chunk(5, audio = audio(listOf(take(1), second)))))
        val viewModel = UnitListViewModel(testDispatcher)
        viewModel.loadUnits()
        advanceUntilIdle()
        val unit = viewModel.unit(5)

        viewModel.cycleTake(unit, direction = 1)
        viewModel.selectCurrentTake(unit)
        advanceUntilIdle()

        val owner = unit.audio.owner
        coVerify { store.setSelected(owner, second.id) }
        assertEquals(second, unit.audio.getSelectedTake())
        val model = viewModel.uiState.value.units.single()
        assertEquals(second, model.selectedTake, "and republished into ui state")
        assertTrue(model.hasContent)
        assertNull(viewModel.uiState.value.saveError)
    }

    /** A selection that is not saved changes nothing on screen, and the failure is shown. */
    @Test
    fun `selectCurrentTake that fails to save keeps the old selection and shows the failure`() =
        runTest(testDispatcher) {
            val first = take(1)
            stubNavigation(chapter(chapterSort, chunk(5, audio = audio(listOf(first, take(2)), selected = first))))
            val viewModel = UnitListViewModel(testDispatcher)
            viewModel.loadUnits()
            advanceUntilIdle()
            val unit = viewModel.unit(5)

            viewModel.cycleTake(unit, direction = 1)
            coEvery { store.setSelected(any(), any()) } throws IllegalStateException("simulated save failure")
            viewModel.selectCurrentTake(unit)
            advanceUntilIdle()

            assertEquals("take1.wav", viewModel.uiState.value.units.single().selectedTake?.name)
            assertTrue(viewModel.uiState.value.saveError != null, "the failure is shown")
        }

    /** Deleting the last take must not leave the browse index pointing past the end. */
    @Test
    fun `deleteTake clamps the browse index and drops the take from the count`() =
        runTest(testDispatcher) {
            val second = take(2)
            stubNavigation(chapter(chapterSort, chunk(5, audio = audio(listOf(take(1), second)))))
            val viewModel = UnitListViewModel(testDispatcher)
            viewModel.loadUnits()
            advanceUntilIdle()
            val unit = viewModel.unit(5)
            viewModel.cycleTake(unit, direction = 1)

            viewModel.deleteTake(unit, second)
            advanceUntilIdle()

            coVerify { store.markDeleted(second.id!!, any(), any()) }
            assertEquals(0, viewModel.uiState.value.currentTakeIndices[5], "clamped to the last live take")
            assertEquals(1, viewModel.uiState.value.units.single().takes)
        }
}
