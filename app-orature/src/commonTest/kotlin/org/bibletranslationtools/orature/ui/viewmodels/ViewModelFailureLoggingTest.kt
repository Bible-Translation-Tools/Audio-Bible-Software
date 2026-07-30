package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards [launchLogged] itself.
 *
 * Worth its own test because the helper is invoked from ~60 call sites but is easy to break in a
 * way nothing else notices: it was briefly written as `return launchLogged(...)` — infinitely
 * recursive — which compiles perfectly and only fails as a `StackOverflowError` the first time a
 * ViewModel launches anything. The whole app still built and every other test passed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelFailureLoggingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Subject : ViewModel() {
        var ran = false
        fun succeed() = launchLogged { ran = true }
        fun fail() = launchLogged { error("deliberate") }
    }

    @Test
    fun `launchLogged runs its block on the viewModel scope`() = runTest(dispatcher) {
        val subject = Subject()
        subject.succeed()
        advanceUntilIdle()
        assertTrue(subject.ran, "launchLogged never executed its block")
    }

    @Test
    fun `launchLogged absorbs a failure instead of escaping or recursing`() = runTest(dispatcher) {
        val subject = Subject()
        val job = subject.fail()
        advanceUntilIdle()
        assertTrue(job.isCompleted, "the failing coroutine never completed")
        assertFalse(subject.ran)
    }
}
