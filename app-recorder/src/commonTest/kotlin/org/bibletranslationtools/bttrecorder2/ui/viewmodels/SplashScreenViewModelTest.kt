package org.bibletranslationtools.bttrecorder2.ui.viewmodels

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
class SplashScreenViewModelTest {

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
    fun `init progress is formatted and the splash is left when it finishes`() = runTest(testDispatcher) {
        coEvery { initApp.initAppSuspend(any()) } coAnswers {
            val onProgress = firstArg<(ProgressStatus) -> Unit>()
            onProgress(ProgressStatus(titleKey = "Initializing %s", titleMessage = "sources", percent = 0.5))
            onProgress(ProgressStatus(subTitleKey = "Loading %s", subTitleMessage = "en_ulb"))
        }
        val viewModel = SplashScreenViewModel()
        assertFalse(viewModel.initComplete)

        viewModel.startInit()
        advanceUntilIdle()

        assertEquals("Initializing sources", viewModel.progressTitle)
        assertEquals("Loading en_ulb", viewModel.progressBody)
        assertEquals(0.5, viewModel.progress)
        assertTrue(viewModel.initComplete)
    }

    /** Before, a failed init escaped the splash's LaunchedEffect and nothing handled it. */
    @Test
    fun `a failed init still lets the user reach the main menu`() = runTest(testDispatcher) {
        coEvery { initApp.initAppSuspend(any()) } throws IllegalStateException("database is locked")
        val viewModel = SplashScreenViewModel()

        viewModel.startInit()
        advanceUntilIdle()

        assertTrue(viewModel.initComplete)
    }

    @Test
    fun `starting twice runs init once`() = runTest(testDispatcher) {
        coEvery { initApp.initAppSuspend(any()) } returns Unit
        val viewModel = SplashScreenViewModel()

        viewModel.startInit()
        viewModel.startInit()
        advanceUntilIdle()

        coVerify(exactly = 1) { initApp.initAppSuspend(any()) }
    }
}
