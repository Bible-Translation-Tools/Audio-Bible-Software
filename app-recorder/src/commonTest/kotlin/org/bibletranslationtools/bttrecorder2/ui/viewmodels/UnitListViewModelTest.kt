package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import com.jakewharton.rxrelay2.ReplayRelay
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.bibletranslationtools.otter.common.data.workbook.Book
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.DateHolder
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
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
import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
 * `AssociatedAudio` and `Take` are real rather than mocked. Every assertion about take counts,
 * selection and browse indices is a statement about relay behaviour, and stubbing
 * `getAllTakes()`/`getSelectedTake()` would assert only that the stubs were returned.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnitListViewModelTest : KoinTest {

    private val workbookRepository: IWorkbookRepository = mockk()
    private val collectionRepository: ICollectionRepository = mockk()
    private val appPreferences: IAppPreferences = mockk()
    private val audioPlayerConnectionFactory: AudioPlayerConnectionFactory = mockk(relaxed = true)

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
        createdTimestamp = LocalDate.of(2026, 1, number.coerceIn(1, 28))
    ).also { if (deleted) it.deletedTimestamp.accept(DateHolder.now()) }

    /** A real [AssociatedAudio] holding [takes], with [selected] pushed onto the selection relay. */
    private fun audio(takes: List<Take> = emptyList(), selected: Take? = null) =
        AssociatedAudio(ReplayRelay.create<Take>().also { relay -> takes.forEach(relay::accept) })
            .also { if (selected != null) it.selectTake(selected) }

    private fun chunk(
        sort: Int,
        contentType: ContentType = ContentType.TEXT,
        audio: AssociatedAudio = audio()
    ): Chunk = mockk {
        every { this@mockk.sort } returns sort
        every { this@mockk.contentType } returns contentType
        every { this@mockk.audio } returns audio
        every { hasSelectedAudio() } returns (audio.getSelectedTake() != null)
    }

    private fun chapter(sort: Int, chunks: kotlinx.coroutines.flow.Flow<List<Chunk>>): Chapter =
        mockk {
            every { this@mockk.sort } returns sort
            every { observableFlowChunks } returns chunks
        }

    /**
     * Wires navState → collections → workbook → target book chapters, the chain loadUnits walks.
     * [chapters] are what the target book emits; [nav] defaults to a fully-populated position.
     */
    private fun stubNavigation(
        chapters: List<Chapter>,
        nav: ActiveNavState = ActiveNavState(sourceId, targetId, chapterSort, unitSort = -1),
        sourceCollection: Collection? = mockk(),
        targetCollection: Collection? = mockk()
    ): Workbook {
        every { appPreferences.navState } returns flowOf(nav)
        coEvery { collectionRepository.getProjectSuspend(sourceId) } returns sourceCollection
        coEvery { collectionRepository.getProjectSuspend(targetId) } returns targetCollection

        val targetBook: Book = mockk { every { chaptersFlow } returns chapters.asFlowOfEach() }
        val workbook: Workbook = mockk(relaxed = true) {
            every { target } returns targetBook
        }
        if (sourceCollection != null && targetCollection != null) {
            every { workbookRepository.get(sourceCollection, targetCollection) } returns workbook
        }
        return workbook
    }

    private fun List<Chapter>.asFlowOfEach(): kotlinx.coroutines.flow.Flow<Chapter> =
        kotlinx.coroutines.flow.flow { forEach { emit(it) } }

    // ── loadUnits: success ───────────────────────────────────────────────────────────────

    @Test
    fun `loadUnits populates units from the active chapter`() = runTest(testDispatcher) {
        val chunks = listOf(chunk(sort = 1), chunk(sort = 2))
        val workbook = stubNavigation(listOf(chapter(chapterSort, flowOf(chunks))))
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertSame(workbook, state.workbook)
        assertEquals(chapterSort, state.chapter?.sort)
        assertEquals(listOf(1, 2), state.units.map { it.unit.sort })
        assertFalse(state.isLoading, "loading must clear once the chunks arrive")
        assertNull(state.error)
    }

    /**
     * The chapter's own compiled take lives on `chapter.audio` and belongs to the chapter list;
     * this screen is exclusively per-verse, so the META chunk must not become a row.
     */
    @Test
    fun `loadUnits drops the chapter meta chunk`() = runTest(testDispatcher) {
        val chunks = listOf(
            chunk(sort = 1, contentType = ContentType.META),
            chunk(sort = 2, contentType = ContentType.TEXT)
        )
        stubNavigation(listOf(chapter(chapterSort, flowOf(chunks))))
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        assertEquals(
            listOf(2),
            viewModel.uiState.value.units.map { it.unit.sort },
            "only TEXT chunks are verses"
        )
    }

    @Test
    fun `loadUnits counts only takes that are not deleted`() = runTest(testDispatcher) {
        val live = take(1)
        val chunks = listOf(
            chunk(sort = 1, audio = audio(takes = listOf(live, take(2, deleted = true))))
        )
        stubNavigation(listOf(chapter(chapterSort, flowOf(chunks))))
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.units.single().takes)
    }

    @Test
    fun `loadUnits reports each unit's selected take`() = runTest(testDispatcher) {
        val selected = take(2)
        val chunks = listOf(
            chunk(sort = 1, audio = audio(takes = listOf(take(1), selected), selected = selected))
        )
        stubNavigation(listOf(chapter(chapterSort, flowOf(chunks))))
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()

        val unit = viewModel.uiState.value.units.single()
        assertSame(selected, unit.selectedTake)
        assertTrue(unit.hasContent)
    }

    /**
     * The browse index starts on whatever take is selected, not at 0 — otherwise reopening the
     * screen shows take 1 while the checkmark sits on take 3.
     */
    @Test
    fun `loadUnits points the browse index at the selected take`() = runTest(testDispatcher) {
        val selected = take(3)
        val chunks = listOf(
            chunk(
                sort = 7,
                audio = audio(takes = listOf(take(1), take(2), selected), selected = selected)
            )
        )
        stubNavigation(listOf(chapter(chapterSort, flowOf(chunks))))
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
     * loadUnits keeps collecting for the ViewModel's lifetime, so re-entering the screen must not
     * start a second collection — that leaked a subscription and flashed the spinner.
     */
    @Test
    fun `loadUnits is a no-op while a load is already in flight`() = runTest(testDispatcher) {
        val chunkFlow = MutableSharedFlow<List<Chunk>>()
        stubNavigation(listOf(chapter(chapterSort, chunkFlow)))
        val viewModel = UnitListViewModel(testDispatcher)

        viewModel.loadUnits()
        advanceUntilIdle()
        viewModel.loadUnits()
        advanceUntilIdle()
        chunkFlow.emit(listOf(chunk(sort = 1)))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.units.size)
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
        stubNavigation(chapters = emptyList(), targetCollection = null)
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
            val workbook = stubNavigation(listOf(chapter(sort = 99, flowOf(emptyList()))))
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
        val chunkAudio = audio(takes = listOf(take(1), take(2)))
        val unit = chunk(sort = 5, audio = chunkAudio)
        stubNavigation(listOf(chapter(chapterSort, flowOf(listOf(unit)))))
        val viewModel = UnitListViewModel(testDispatcher)
        viewModel.loadUnits()
        advanceUntilIdle()

        viewModel.cycleTake(unit, direction = 1)
        assertEquals(1, viewModel.uiState.value.currentTakeIndices[5])

        viewModel.cycleTake(unit, direction = 1)
        assertEquals(0, viewModel.uiState.value.currentTakeIndices[5], "wraps past the last take")

        viewModel.cycleTake(unit, direction = -1)
        assertEquals(1, viewModel.uiState.value.currentTakeIndices[5], "wraps below the first take")
    }

    @Test
    fun `cycleTake does nothing when the unit has no takes`() = runTest(testDispatcher) {
        val unit = chunk(sort = 5)
        stubNavigation(listOf(chapter(chapterSort, flowOf(listOf(unit)))))
        val viewModel = UnitListViewModel(testDispatcher)
        viewModel.loadUnits()
        advanceUntilIdle()

        viewModel.cycleTake(unit, direction = 1)

        assertEquals(0, viewModel.uiState.value.currentTakeIndices[5])
    }

    /**
     * Selecting publishes the new selection into ui state as well as onto the relay — without the
     * republish nothing in the state object changes, so Compose never recomposes and the checkmark
     * appears not to move.
     */
    @Test
    fun `selectCurrentTake publishes the browsed take as selected`() = runTest(testDispatcher) {
        val second = take(2)
        val chunkAudio = audio(takes = listOf(take(1), second))
        val unit = chunk(sort = 5, audio = chunkAudio)
        every { unit.hasSelectedAudio() } answers { chunkAudio.getSelectedTake() != null }
        stubNavigation(listOf(chapter(chapterSort, flowOf(listOf(unit)))))
        val viewModel = UnitListViewModel(testDispatcher)
        viewModel.loadUnits()
        advanceUntilIdle()

        viewModel.cycleTake(unit, direction = 1)
        viewModel.selectCurrentTake(unit)

        assertSame(second, chunkAudio.getSelectedTake(), "persisted onto the relay")
        val model = viewModel.uiState.value.units.single()
        assertSame(second, model.selectedTake, "and republished into ui state")
        assertTrue(model.hasContent)
    }

    /** Deleting the last take must not leave the browse index pointing past the end. */
    @Test
    fun `deleteTake clamps the browse index and drops the take from the count`() =
        runTest(testDispatcher) {
            val second = take(2)
            val chunkAudio = audio(takes = listOf(take(1), second))
            val unit = chunk(sort = 5, audio = chunkAudio)
            every { unit.hasSelectedAudio() } answers { chunkAudio.getSelectedTake() != null }
            stubNavigation(listOf(chapter(chapterSort, flowOf(listOf(unit)))))
            val viewModel = UnitListViewModel(testDispatcher)
            viewModel.loadUnits()
            advanceUntilIdle()
            viewModel.cycleTake(unit, direction = 1)

            viewModel.deleteTake(unit, second)

            assertTrue(second.isDeleted())
            assertEquals(0, viewModel.uiState.value.currentTakeIndices[5], "clamped to the last live take")
            assertEquals(1, viewModel.uiState.value.units.single().takes)
        }
}
