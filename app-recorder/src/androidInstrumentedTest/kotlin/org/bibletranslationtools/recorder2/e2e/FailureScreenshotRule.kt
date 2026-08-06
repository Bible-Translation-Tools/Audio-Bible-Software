package org.bibletranslationtools.recorder2.e2e

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

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

        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            SCREENSHOT_DIR_NAME,
        ).apply { mkdirs() }
        val file = File(dir, "$safeName.png")
        try {
            val ok = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(file)
            E2eLog.step(
                if (ok) "SCREENSHOT saved ${file.absolutePath}"
                else "SCREENSHOT takeScreenshot returned false for $safeName"
            )
        } catch (t: Throwable) {
            E2eLog.step("SCREENSHOT error for $safeName: ${t.message}")
        }
    }

    companion object {
        const val SCREENSHOT_DIR_NAME = "e2e-failures"
    }
}
