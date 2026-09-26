package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.collections.BookEditionState
import org.bibletranslationtools.otter.common.domain.collections.BookRebase
import org.bibletranslationtools.otter.common.domain.collections.BookUpgradePlan
import org.bibletranslationtools.otter.common.domain.collections.ChapterOutcome
import org.bibletranslationtools.otter.common.domain.collections.ChapterUpgradePlan
import org.bibletranslationtools.otter.common.domain.collections.EditionChoice
import org.bibletranslationtools.otter.common.domain.collections.UpgradeBookEdition
import org.bibletranslationtools.shared.preferences.ActiveNavState
import org.bibletranslationtools.shared.preferences.IAppPreferences
import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Choosing an edition for a book, previewing the move and applying it. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditionChangeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val english = Language("en", "English", "English", "ltr", true, "")

    private fun edition(id: Int, version: String, issued: String) = ResourceMetadata(
        conformsTo = "rc0.2", creator = "WA", description = "", format = "text/usfm", identifier = "ulb",
        issued = LocalDate.parse(issued), language = english, modified = LocalDate.parse(issued),
        publisher = "", subject = "Bible", type = ContainerType.Bundle, title = "ulb $version",
        version = version, license = "", path = File("/rc/$id"), id = id
    )

    private val v12 = edition(1, "12", "2017-11-29")
    private val v2407 = edition(2, "24-07", "2024-07-12")
    private val projectBook = 50
    private val newSourceBook = 70

    private val plan = BookUpgradePlan(
        projectBookId = projectBook,
        bookSlug = "act",
        from = v12,
        to = v2407,
        chapters = listOf(ChapterUpgradePlan("act_19", 19, ChapterOutcome.HELD_BACK, null)),
        rebase = BookRebase(projectBook, v2407, newSourceBook, emptyList(), emptyList())
    )

    private val upgrade = mockk<UpgradeBookEdition>(relaxed = true)
    private val preferences = mockk<IAppPreferences>(relaxed = true)

    private fun startWith(nav: ActiveNavState) {
        coEvery { upgrade.editionState(projectBook) } returns BookEditionState(
            projectBook, v12, null, listOf(EditionChoice(v2407, newer = true, distinguishingCode = null)), emptyList()
        )
        coEvery { upgrade.plan(projectBook, v2407) } returns plan
        every { preferences.navState } returns flowOf(nav)
    }

    private fun viewModel() = EditionChangeViewModel(projectBook, upgrade, preferences)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.settle(done: () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    private fun TestScope.applied(viewModel: EditionChangeViewModel): EditionChangeState {
        settle { viewModel.state.value is EditionChangeState.Choosing }
        viewModel.preview()
        settle { viewModel.state.value is EditionChangeState.Previewing }
        viewModel.apply()
        settle { viewModel.state.value is EditionChangeState.Done }
        return viewModel.state.value
    }

    @Test
    fun `the newer edition is preselected`() = runTest(dispatcher) {
        startWith(ActiveNavState())
        val viewModel = viewModel()

        settle { viewModel.state.value is EditionChangeState.Choosing }

        val state = assertIs<EditionChangeState.Choosing>(viewModel.state.value)
        assertEquals(v2407, state.selected?.edition)
    }

    @Test
    fun `applying reports the held-back chapters and follows the active book to its new source`() = runTest(dispatcher) {
        startWith(ActiveNavState(workbookSourceId = 60, workbookTargetId = projectBook, chapterSort = 19))
        val viewModel = viewModel()

        val done = assertIs<EditionChangeState.Done>(applied(viewModel))

        assertEquals(listOf(19), done.heldBack)
        coVerify { upgrade.apply(plan) }
        coVerify { preferences.setActiveWorkbook(newSourceBook, projectBook) }
        coVerify { preferences.setActiveChapter(19) }
    }

    @Test
    fun `another active book is left alone`() = runTest(dispatcher) {
        startWith(ActiveNavState(workbookSourceId = 60, workbookTargetId = 99))
        val viewModel = viewModel()

        assertIs<EditionChangeState.Done>(applied(viewModel))

        coVerify(exactly = 0) { preferences.setActiveWorkbook(any(), any()) }
    }
}
