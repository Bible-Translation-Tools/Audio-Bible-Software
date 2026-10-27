package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionFingerprintRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DescribeSourceEditions
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The wizard's edition step: shown only when a source has several editions installed. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectCreationViewModelEditionTest {

    private val dispatcher = StandardTestDispatcher()
    private val english = Language("en", "English", "English", "ltr", true, "")
    private val french = Language("fr", "Français", "French", "ltr", true, "")

    private fun edition(id: Int, language: Language, identifier: String, version: String, issued: String) =
        ResourceMetadata(
            conformsTo = "rc0.2", creator = "WA", description = "", format = "text/usfm", identifier = identifier,
            issued = LocalDate.parse(issued), language = language, modified = LocalDate.parse(issued),
            publisher = "", subject = "Bible", type = ContainerType.Bundle, title = "$identifier $version",
            version = version, license = "", path = File("/rc/$id"), id = id
        )

    private val v12 = edition(1, english, "ulb", "12", "2017-11-29")
    private val v2407 = edition(2, english, "ulb", "24-07", "2024-07-12")
    private val f10 = edition(3, french, "f10", "1910", "2019-10-01")

    private fun startWith(vararg sources: ResourceMetadata) {
        val metadata = mockk<IResourceMetadataRepository> {
            every { getAllSources() } returns Single.just(sources.toList())
            coEvery { getAllSourcesSuspend() } returns sources.toList()
        }
        val languages = mockk<ILanguageRepository> {
            every { getAvailableGatewaySources() } returns Single.just(emptyList())
            every { getAll() } returns Single.just(listOf(english, french))
        }
        val fingerprints = mockk<IEditionFingerprintRepository>()
        coEvery { fingerprints.get(any()) } returns null
        startKoin {
            modules(module {
                single { metadata }
                single { languages }
                single<ICollectionRepository> { mockk(relaxed = true) }
                single<CreateProject> { mockk(relaxed = true) }
                single<ImportProjectUseCase> { mockk(relaxed = true) }
                single { DescribeSourceEditions(metadata, fingerprints) }
            })
        }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    /** Lets the view model's IO work finish and its results land on the main dispatcher. */
    private fun TestScope.settle(done: () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `a source with one edition goes straight to the target language`() = runTest(dispatcher) {
        startWith(v12, f10)
        val viewModel = ProjectCreationViewModel()

        viewModel.selectSource(f10)
        settle { viewModel.uiState.value.currentStep != WizardStep.SOURCE }

        assertEquals(WizardStep.TARGET_LANGUAGE, viewModel.uiState.value.currentStep)
        assertEquals(f10, viewModel.uiState.value.selectedSource)
    }

    @Test
    fun `a source with several editions offers them newest first`() = runTest(dispatcher) {
        startWith(v12, v2407)
        val viewModel = ProjectCreationViewModel()

        viewModel.selectSource(v2407)
        settle { viewModel.uiState.value.currentStep != WizardStep.SOURCE }

        val state = viewModel.uiState.value
        assertEquals(WizardStep.EDITION, state.currentStep)
        assertEquals(listOf(v2407, v12), state.editions.map { it.summary.edition })
        assertTrue(state.editions[0].isNewest)
        assertFalse(state.editions[0].isOlder)
        assertTrue(state.editions[1].isOlder)
    }

    @Test
    fun `editions with the same dates aren't marked older`() = runTest(dispatcher) {
        val sibling = v12.copy(id = 4, path = File("/rc/4"))
        startWith(v12, sibling)
        val viewModel = ProjectCreationViewModel()

        viewModel.selectSource(v12)
        settle { viewModel.uiState.value.currentStep != WizardStep.SOURCE }

        assertTrue(viewModel.uiState.value.editions.none { it.isOlder })
    }

    @Test
    fun `choosing an older edition uses it, and back returns to the edition step`() = runTest(dispatcher) {
        startWith(v12, v2407)
        val viewModel = ProjectCreationViewModel()
        viewModel.selectSource(v2407)
        settle { viewModel.uiState.value.currentStep == WizardStep.EDITION }

        viewModel.selectEdition(v12)
        assertEquals(v12, viewModel.uiState.value.selectedSource)
        assertEquals(WizardStep.TARGET_LANGUAGE, viewModel.uiState.value.currentStep)

        viewModel.navigateBack()
        assertEquals(WizardStep.EDITION, viewModel.uiState.value.currentStep)
        viewModel.navigateBack()
        assertEquals(WizardStep.SOURCE, viewModel.uiState.value.currentStep)
    }
}
