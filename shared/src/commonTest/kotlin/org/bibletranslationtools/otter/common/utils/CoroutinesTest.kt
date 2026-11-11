package org.bibletranslationtools.otter.common.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutinesTest {

    private val logger = LoggerFactory.getLogger(CoroutinesTest::class.java)
    private val boom = IOException("injected")

    @Test
    fun `a storage call runs on the io dispatcher and returns its value`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "test-io") }
        try {
            var thread = ""

            val value = AppDispatchers(io = executor.asCoroutineDispatcher()).storageCall(logger, { "reading" }) {
                thread = Thread.currentThread().name
                42
            }

            assertEquals(42, value)
            assertTrue(thread.startsWith("test-io"), thread)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `a storage call that fails rethrows the exception as it was thrown`() = runTest {
        val dispatchers = AppDispatchers(io = UnconfinedTestDispatcher(testScheduler))

        val thrown = assertFailsWith<IOException> { dispatchers.storageCall(logger, { "reading" }) { throw boom } }

        assertSame(boom, thrown)
    }

    @Test
    fun `runCatchingCancellable captures a failure and lets cancellation through`() {
        assertSame(boom, runCatchingCancellable { throw boom }.exceptionOrNull())
        assertEquals(1, runCatchingCancellable { 1 }.getOrNull())
        assertFailsWith<CancellationException> { runCatchingCancellable { throw CancellationException("cancelled") } }
    }

    @Test
    fun `bestEffort returns the value, or null after a failure, and lets cancellation through`() {
        assertEquals(1, bestEffort(logger, "an optional step") { 1 })
        assertNull(bestEffort<Int>(logger, "an optional step") { throw boom })
        assertFailsWith<CancellationException> { bestEffort(logger, "an optional step") { throw CancellationException("cancelled") } }
    }

    @Test
    fun `a job that fails in the app scope is published and does not stop the scope`() = runTest {
        val scope = AppScope(AppDispatchers(default = UnconfinedTestDispatcher(testScheduler)))
        val failure = async(UnconfinedTestDispatcher(testScheduler)) { scope.failures.first() }

        scope.launch { throw boom }
        var ranAfterwards = false
        scope.launch { ranAfterwards = true }

        assertSame(boom, failure.await().error)
        assertTrue(ranAfterwards, "a later job in the same scope still runs")
    }

    @Test
    fun `a failure reported by hand is published with what was being done`() = runTest {
        val scope = AppScope(AppDispatchers(default = UnconfinedTestDispatcher(testScheduler)))
        val failure = async(UnconfinedTestDispatcher(testScheduler)) { scope.failures.first() }

        scope.report("saving take 3", boom)

        assertEquals(BackgroundFailure("saving take 3", boom), failure.await())
    }
}
