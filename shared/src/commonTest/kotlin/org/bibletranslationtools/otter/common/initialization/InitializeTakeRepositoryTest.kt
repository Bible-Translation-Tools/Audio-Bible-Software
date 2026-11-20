package org.bibletranslationtools.otter.common.initialization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import kotlin.test.Test
import kotlin.test.assertFailsWith

class InitializeTakeRepositoryTest {

    private val takes = mockk<ITakeRepository>(relaxUnitFun = true)
    private val initializer = InitializeTakeRepository(takes)

    @Test
    fun `removes takes whose files are gone, then the expired ones`() = runTest {
        initializer.execSuspend {}

        coVerifyOrder {
            takes.removeNonExistentTakesSuspend()
            takes.deleteExpiredTakesSuspend()
        }
    }

    @Test
    fun `a failure in the first step fails the initializer and skips the second`() = runTest {
        coEvery { takes.removeNonExistentTakesSuspend() } throws IllegalStateException("database is locked")

        assertFailsWith<IllegalStateException> { initializer.execSuspend {} }

        coVerify(exactly = 0) { takes.deleteExpiredTakesSuspend(any()) }
    }

    @Test
    fun `the Rx exec does the same work`() {
        initializer.exec(mockk()).blockingAwait()

        coVerify(exactly = 1) { takes.removeNonExistentTakesSuspend() }
        coVerify(exactly = 1) { takes.deleteExpiredTakesSuspend() }
    }
}
