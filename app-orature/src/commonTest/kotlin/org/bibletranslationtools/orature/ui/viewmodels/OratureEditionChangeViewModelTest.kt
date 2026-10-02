package org.bibletranslationtools.orature.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.collections.EditionChoice
import org.bibletranslationtools.otter.common.domain.collections.EditionRelation
import org.bibletranslationtools.otter.common.domain.collections.ProjectUpgradePlan
import org.bibletranslationtools.otter.common.domain.collections.UpgradeProjectEdition
import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Moving a project, or one book, to another edition: choose, preview, apply (O1). */
@OptIn(ExperimentalCoroutinesApi::class)
class OratureEditionChangeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val english = Language("en", "English", "English", "ltr", true, "")

    private fun edition(id: Int, version: String, issued: String) = ResourceMetadata(
        conformsTo = "rc0.2", creator = "WA", description = "", format = "text/usfm", identifier = "ulb",
        issued = LocalDate.parse(issued), language = english, modified = LocalDate.parse(issued),
        publisher = "", subject = "Bible", type = ContainerType.Bundle, title = "ULB",
        version = version, license = "", path = File("/rc/$id"), id = id
    )

    private val v12 = edition(1, "12", "2017-11-29")
    private val v2407 = edition(2, "24-07", "2024-07-12")
    private val target = OratureEditionChangeTarget(
        projectBookIds = listOf(10, 11),
        editions = listOf(v12),
        bookTitles = mapOf(10 to "Genesis", 11 to "Exodus"),
        singleBook = false
    )
    private val plan = ProjectUpgradePlan(v2407, books = emptyList(), unavailable = listOf("exo"))
    private val use = mockk<UpgradeProjectEdition>(relaxed = true)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { use.choices(listOf(v12)) } returns listOf(EditionChoice(v2407, EditionRelation.NEWER, distinguishingCode = null))
        coEvery { use.plan(listOf(10, 11), v2407, any()) } returns plan
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.settle(done: (OratureEditionChangeState) -> Boolean, vm: OratureEditionChangeViewModel) {
        repeat(200) {
            advanceUntilIdle()
            if (done(vm.state.value)) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `the newer edition is preselected`() = runTest(dispatcher) {
        val vm = OratureEditionChangeViewModel(target, use)

        settle({ it is OratureEditionChangeState.Choosing }, vm)

        assertEquals(v2407, assertIs<OratureEditionChangeState.Choosing>(vm.state.value).selected?.edition)
    }

    @Test
    fun `every book is planned and then moved`() = runTest(dispatcher) {
        val vm = OratureEditionChangeViewModel(target, use)
        settle({ it is OratureEditionChangeState.Choosing }, vm)

        vm.preview()
        settle({ it is OratureEditionChangeState.Previewing }, vm)
        assertEquals(listOf("exo"), assertIs<OratureEditionChangeState.Previewing>(vm.state.value).plan.unavailable)

        vm.apply()
        settle({ it is OratureEditionChangeState.Done }, vm)
        assertIs<OratureEditionChangeState.Done>(vm.state.value)
        coVerify { use.apply(plan, any()) }
    }

    @Test
    fun `back from the preview returns to the choice`() = runTest(dispatcher) {
        val vm = OratureEditionChangeViewModel(target, use)
        settle({ it is OratureEditionChangeState.Choosing }, vm)
        vm.preview()
        settle({ it is OratureEditionChangeState.Previewing }, vm)

        vm.back()

        assertEquals(v2407, assertIs<OratureEditionChangeState.Choosing>(vm.state.value).selected?.edition)
    }
}
