package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OratureSplashViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val initApp = mockk<InitializeApp>()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        startKoin { modules(module { single { initApp } }) }
    }

    @AfterTest
    fun teardown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun `init progress is shown and the splash is left when it finishes`() = runTest(testDispatcher) {
        coEvery { initApp.initApp(any()) } coAnswers {
            val onProgress = firstArg<(ProgressStatus) -> Unit>()
            onProgress(ProgressStatus(titleKey = "initializingSources", percent = 0.5))
            onProgress(ProgressStatus(subTitleKey = "loadingSomething"))
        }
        val viewModel = OratureSplashViewModel()
        assertFalse(viewModel.initComplete)

        viewModel.startInit()
        advanceUntilIdle()

        assertEquals("initializingSources", viewModel.progressTitle)
        assertEquals("loadingSomething", viewModel.progressBody)
        assertEquals(0.5, viewModel.progress)
        assertTrue(viewModel.initComplete)
    }

    /** A failed seed step is recoverable in a way a dead splash screen is not. */
    @Test
    fun `a failed init still lets the user reach Home`() = runTest(testDispatcher) {
        coEvery { initApp.initApp(any()) } throws IllegalStateException("database is locked")
        val viewModel = OratureSplashViewModel()

        viewModel.startInit()
        advanceUntilIdle()

        assertTrue(viewModel.initComplete)
    }

    @Test
    fun `starting twice runs init once`() = runTest(testDispatcher) {
        coEvery { initApp.initApp(any()) } returns Unit
        val viewModel = OratureSplashViewModel()

        viewModel.startInit()
        viewModel.startInit()
        advanceUntilIdle()

        coVerify(exactly = 1) { initApp.initApp(any()) }
    }

    /** The ViewModel is gone: init was not finished, so it is not reported as finished or failed. */
    @Test
    fun `init cancelled with the ViewModel does not complete`() = runTest(testDispatcher) {
        coEvery { initApp.initApp(any()) } coAnswers { awaitCancellation() }
        val viewModel = OratureSplashViewModel()
        viewModel.startInit()
        advanceUntilIdle()

        viewModel.viewModelScope.cancel()
        advanceUntilIdle()

        assertFalse(viewModel.initComplete)
    }

    /** Something inside init was cancelled, not the ViewModel: handled as any other failed init. */
    @Test
    fun `a cancellation from inside init still lets the user reach Home`() = runTest(testDispatcher) {
        coEvery { initApp.initApp(any()) } throws CancellationException("timed out")
        val viewModel = OratureSplashViewModel()

        viewModel.startInit()
        advanceUntilIdle()

        assertTrue(viewModel.initComplete)
    }
}
