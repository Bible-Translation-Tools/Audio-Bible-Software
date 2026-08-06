package org.bibletranslationtools.recorder2.e2e

import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Innermost rule (order after ActivityScenarioRule) so the PNG is taken while the
 * activity is still up — RunListener fires too late, after scenario.close().
 */
class FailureScreenshotRule : TestWatcher() {

    override fun failed(e: Throwable, description: Description) {
        val safeName = buildString {
            append(description.className.substringAfterLast('.'))
            append('_')
            append(description.methodName ?: "unknown")
        }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        captureE2eScreenshot(safeName)
    }

    companion object {
        const val SCREENSHOT_DIR_NAME = "e2e-failures"
    }
}
