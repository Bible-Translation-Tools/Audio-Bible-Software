package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressFilterTest {
    @Test
    fun `each progress matches exactly one filter`() {
        val expected = mapOf(
            0.0 to ProgressFilter.NOT_STARTED,
            0.01 to ProgressFilter.IN_PROGRESS,
            0.99 to ProgressFilter.IN_PROGRESS,
            1.0 to ProgressFilter.COMPLETED
        )
        expected.forEach { (progress, filter) ->
            assertEquals(listOf(filter), ProgressFilter.entries.filter { it.matches(progress) }, "progress $progress")
        }
    }
}
