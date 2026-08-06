package org.bibletranslationtools.recorder2.e2e

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

/**
 * UiAutomator helpers for recorder Android e2e.
 *
 * Prefer [ActivityScenarioRule] over ComposeTestRule: the latter replaces the frame clock so
 * taps show ripple but NavHost never recomposes (unlike a normal installDebug launch).
 *
 * App navigation is sync on the click path (collected nav state, navigate before prefs I/O).
 * Do not reintroduce `scope.launch { navState.first() }` for MainMenu Record — under an
 * instrumented test dispatcher / idling that coroutine often never runs and home stays stuck.
 */

internal fun waitForMainMenuAfterSplash(timeoutMillis: Long = 120_000) {
    E2eLog.step("WAIT splash→main menu (timeout=${timeoutMillis}ms)")
    captureE2eScreenshot("main-menu-wait")
    val device = uiDevice()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        if (device.hasObject(By.desc("Files")) && device.hasObject(By.desc("Record"))) {
            E2eLog.step("FOUND Files+Record (main menu)")
            return
        }
        SystemClock.sleep(500)
    }
    assertTrue("Timed out waiting for main menu after splash (Files + Record)", false)
}

internal fun captureE2eScreenshot(label: String) {
    val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = java.io.File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        FailureScreenshotRule.SCREENSHOT_DIR_NAME,
    ).apply { mkdirs() }
    val file = java.io.File(dir, "$safe.png")
    try {
        val ok = uiDevice().takeScreenshot(file)
        E2eLog.step(
            if (ok) "SCREENSHOT saved ${file.absolutePath}"
            else "SCREENSHOT takeScreenshot returned false for $safe"
        )
    } catch (t: Throwable) {
        E2eLog.step("SCREENSHOT error for $safe: ${t.message}")
    }
}

internal fun waitForText(text: String, timeoutMillis: Long = 60_000) {
    E2eLog.step("WAIT text=\"$text\" (timeout=${timeoutMillis}ms)")
    assertTrue(
        "Timed out waiting for text: $text",
        uiDevice().wait(Until.hasObject(By.text(text)), timeoutMillis)
    )
    E2eLog.step("FOUND text=\"$text\"")
}

internal fun waitForTextContains(substring: String, timeoutMillis: Long = 60_000) {
    E2eLog.step("WAIT textContains=\"$substring\" (timeout=${timeoutMillis}ms)")
    assertTrue(
        "Timed out waiting for text containing: $substring",
        uiDevice().wait(Until.hasObject(By.textContains(substring)), timeoutMillis)
    )
    E2eLog.step("FOUND textContains=\"$substring\"")
}

internal fun waitForContentDescription(label: String, timeoutMillis: Long = 120_000) {
    E2eLog.step("WAIT contentDescription=\"$label\" (timeout=${timeoutMillis}ms)")
    assertTrue(
        "Timed out waiting for content description: $label",
        uiDevice().wait(Until.hasObject(By.desc(label)), timeoutMillis)
    )
    E2eLog.step("FOUND contentDescription=\"$label\"")
}

internal fun clickContentDescription(label: String) {
    E2eLog.step("CLICK contentDescription=\"$label\"")
    val device = uiDevice()
    assertTrue(
        "No node with contentDescription \"$label\" to click",
        device.wait(Until.hasObject(By.desc(label)), 5_000)
    )
    device.findObject(By.desc(label)).click()
}

internal fun clickText(text: String) {
    E2eLog.step("CLICK text=\"$text\"")
    val device = uiDevice()
    assertTrue(
        "No node with text \"$text\" to click",
        device.wait(Until.hasObject(By.text(text)), 5_000)
    )
    device.findObject(By.text(text)).click()
}

internal fun clickTextContains(substring: String) {
    E2eLog.step("CLICK textContains=\"$substring\"")
    val device = uiDevice()
    assertTrue(
        "No node with text containing \"$substring\" to click",
        device.wait(Until.hasObject(By.textContains(substring)), 5_000)
    )
    device.findObject(By.textContains(substring)).click()
}

internal fun assertDisplayedContentDescription(label: String) {
    E2eLog.step("ASSERT contentDescription=\"$label\"")
    assertTrue(
        "Expected contentDescription \"$label\" on screen",
        uiDevice().hasObject(By.desc(label))
    )
}

internal fun assertDisplayedText(text: String) {
    E2eLog.step("ASSERT text=\"$text\"")
    assertTrue(
        "Expected text \"$text\" on screen",
        uiDevice().hasObject(By.text(text))
    )
}

internal fun waitForActiveWorkbook(timeoutMillis: Long = 60_000) {
    E2eLog.step("WAIT prefs.hasActiveWorkbook (timeout=${timeoutMillis}ms)")
    val prefs = org.koin.core.context.GlobalContext.get()
        .get<org.bibletranslationtools.shared.preferences.IAppPreferences>()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        val nav = runBlocking { prefs.navState.first() }
        if (nav.hasActiveWorkbook) {
            E2eLog.step(
                "FOUND active workbook source=${nav.workbookSourceId} target=${nav.workbookTargetId} " +
                    "chapter=${nav.chapterSort}"
            )
            return
        }
        SystemClock.sleep(100)
    }
    assertTrue("Timed out waiting for active workbook in app preferences", false)
}

/** MainMenu only shows book/language labels once uiState.hasActiveProject is true. */
internal fun waitForActiveProjectOnMainMenu(timeoutMillis: Long = 60_000) {
    E2eLog.step("WAIT main-menu active project labels (timeout=${timeoutMillis}ms)")
    val device = uiDevice()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        if (device.hasObject(By.textContains("Genesis")) || device.hasObject(By.textContains("Afar"))) {
            E2eLog.step("FOUND active project labels on main menu")
            return
        }
        SystemClock.sleep(100)
    }
    assertTrue(
        "Timed out waiting for Genesis/Afar on main menu (active project UI not ready)",
        false
    )
}

/**
 * After tapping home Record: either the recorder opens, or we landed on Project Management
 * because nav state had no active workbook (common race right after recreate).
 */
internal fun waitForRecorderTransportOrFail(timeoutMillis: Long = 120_000) {
    E2eLog.step("WAIT recorder transport or Project Management (timeout=${timeoutMillis}ms)")
    val device = uiDevice()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        if (device.hasObject(By.desc("Record transport"))) {
            E2eLog.step("FOUND contentDescription=\"Record transport\"")
            return
        }
        if (device.hasObject(By.text("Project Management"))) {
            assertTrue(
                "Home Record opened Project Management instead of the recorder — " +
                    "active workbook was not ready in MainMenu nav state",
                false
            )
        }
        SystemClock.sleep(100)
    }
    assertTrue("Timed out waiting for content description: Record transport", false)
}

/**
 * [RecorderViewModel.loadTarget] is async; [RecorderViewModel.startRecording] no-ops while
 * `associatedAudio` is still null. Wait for the seeded project's header labels so the
 * transport mic click actually engages recording.
 */
internal fun waitForRecorderTargetLoaded(timeoutMillis: Long = 60_000) {
    E2eLog.step("WAIT recorder target labels (timeout=${timeoutMillis}ms)")
    val device = uiDevice()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        // Header is "ULB  Genesis" (source identifier + target book label) once switchToTarget runs.
        if (device.hasObject(By.textContains("Genesis")) ||
            device.hasObject(By.textContains("ULB"))
        ) {
            E2eLog.step("FOUND recorder target labels")
            return
        }
        SystemClock.sleep(100)
    }
    assertTrue("Timed out waiting for recorder target labels (Genesis/ULB)", false)
}

/**
 * Opens the wizard search field, types [query], then taps a row matching [resultSubstring].
 */
internal fun searchAndClickResult(
    query: String,
    resultSubstring: String,
    timeoutMillis: Long = 60_000,
) {
    E2eLog.step("SEARCH query=\"$query\" then click textContains=\"$resultSubstring\"")
    clickContentDescription("Search")
    val device = uiDevice()
    assertTrue(
        "Wizard search did not open (Close search not found)",
        device.wait(Until.hasObject(By.desc("Close search")), 10_000)
    )
    SystemClock.sleep(300)
    InstrumentationRegistry.getInstrumentation().sendStringSync(query)
    waitForTextContains(resultSubstring, timeoutMillis)
    clickTextContains(resultSubstring)
}

internal fun uiDevice(): UiDevice =
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
