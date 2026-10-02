package org.bibletranslationtools.orature.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledResource
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledResources
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ExportSourceEdition
import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class OratureResourcesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val english = Language("en", "English", "English", "ltr", true, "")

    private fun resource(id: Int, usedBy: List<Language>) = InstalledResource(
        edition = ResourceMetadata(
            conformsTo = "rc0.2", creator = "WA", description = "", format = "text/usfm", identifier = "ulb",
            issued = LocalDate.parse("2017-11-29"), language = english, modified = LocalDate.parse("2017-11-29"),
            publisher = "", subject = "Bible", type = ContainerType.Bundle, title = "ULB",
            version = "12", license = "", path = File("/rc/$id"), id = id
        ),
        distinguishingCode = null, sizeBytes = 10L * 1024 * 1024, audioBytes = 0,
        usedByLanguages = usedBy, heldBackChapters = 0, linkedTo = emptyList()
    )

    private val used = resource(1, listOf(Language("fr", "Français", "French", "ltr", true, "")))
    private val unused = resource(2, emptyList())
    private val installed = mockk<InstalledResources>()
    private val exporter = mockk<ExportSourceEdition>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `removing an unused resource reports it and reloads the list`() = runTest(dispatcher) {
        coEvery { installed.list() } returnsMany listOf(listOf(used, unused), listOf(used))
        coEvery { installed.remove(unused.edition) } returns true
        val vm = OratureResourcesViewModel(installed, exporter)
        vm.onOpened()
        advanceUntilIdle()
        assertEquals(20L * 1024 * 1024, vm.uiState.value.totalBytes)

        vm.remove(unused)
        advanceUntilIdle()

        assertIs<OratureResourceRemoval.Removed>(vm.uiState.value.lastRemoval)
        assertEquals(listOf(used), vm.uiState.value.resources)
    }

    @Test
    fun `a resource in use is never sent for removal`() = runTest(dispatcher) {
        coEvery { installed.list() } returns listOf(used)
        val vm = OratureResourcesViewModel(installed, exporter)
        vm.onOpened()
        advanceUntilIdle()

        vm.remove(used)
        advanceUntilIdle()

        coVerify(exactly = 0) { installed.remove(any()) }
    }

    @Test
    fun `opening again shows a resource that stopped being used while the drawer was closed`() = runTest(dispatcher) {
        val noLongerUsed = resource(1, emptyList())
        coEvery { installed.list() } returnsMany listOf(listOf(used), listOf(noLongerUsed))
        val vm = OratureResourcesViewModel(installed, exporter)
        vm.onOpened()
        advanceUntilIdle()

        // Every project moves to another edition while the drawer is closed; the view model lives on.
        vm.onOpened()
        advanceUntilIdle()

        assertEquals(listOf(noLongerUsed), vm.uiState.value.resources)
    }

    @Test
    fun `opening again drops the message about the last removal`() = runTest(dispatcher) {
        coEvery { installed.list() } returnsMany listOf(listOf(used, unused), listOf(used))
        coEvery { installed.remove(unused.edition) } returns true
        val vm = OratureResourcesViewModel(installed, exporter)
        vm.onOpened()
        advanceUntilIdle()
        vm.remove(unused)
        advanceUntilIdle()

        vm.onOpened()
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.lastRemoval)
    }

    @Test
    fun `exporting reports where the zip was written`() = runTest(dispatcher) {
        coEvery { installed.list() } returns listOf(used)
        coEvery { exporter.export(used.edition, File("/out")) } returns File("/out/en_ulb_v12.zip")
        val vm = OratureResourcesViewModel(installed, exporter)
        vm.onOpened()
        advanceUntilIdle()

        vm.export(used, "/out")
        advanceUntilIdle()

        val export = assertIs<OratureResourceExport.Exported>(vm.uiState.value.lastExport)
        assertEquals(File("/out/en_ulb_v12.zip").absolutePath, export.location)
        assertEquals(null, vm.uiState.value.exportingId)
    }

    @Test
    fun `a failed export is reported, not thrown`() = runTest(dispatcher) {
        coEvery { installed.list() } returns listOf(used)
        coEvery { exporter.export(any(), any()) } throws IllegalArgumentException("missing")
        val vm = OratureResourcesViewModel(installed, exporter)
        vm.onOpened()
        advanceUntilIdle()

        vm.export(used, "/out")
        advanceUntilIdle()

        assertIs<OratureResourceExport.Failed>(vm.uiState.value.lastExport)
    }
}
