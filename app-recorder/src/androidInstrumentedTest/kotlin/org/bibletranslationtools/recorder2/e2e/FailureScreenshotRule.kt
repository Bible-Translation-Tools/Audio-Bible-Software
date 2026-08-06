package org.bibletranslationtools.recorder2.e2e

import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Innermost rule so PNGs are taken before ActivityScenarioRule closes the activity. */
class FailureScreenshotRule : TestWatcher() {

    override fun failed(e: Throwable, description: Description) {
        val safeName = buildString {
            append(description.className.substringAfterLast('.'))
            append('_')
            append(description.methodName ?: "unknown")
        }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        captureE2eScreenshot(safeName)
    }
}
