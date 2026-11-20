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
        coEvery { initApp.initAppSuspend(any()) } coAnswers {
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
        coEvery { initApp.initAppSuspend(any()) } throws IllegalStateException("database is locked")
        val viewModel = OratureSplashViewModel()

        viewModel.startInit()
        advanceUntilIdle()

        assertTrue(viewModel.initComplete)
    }

    @Test
    fun `starting twice runs init once`() = runTest(testDispatcher) {
        coEvery { initApp.initAppSuspend(any()) } returns Unit
        val viewModel = OratureSplashViewModel()

        viewModel.startInit()
        viewModel.startInit()
        advanceUntilIdle()

        coVerify(exactly = 1) { initApp.initAppSuspend(any()) }
    }
}
