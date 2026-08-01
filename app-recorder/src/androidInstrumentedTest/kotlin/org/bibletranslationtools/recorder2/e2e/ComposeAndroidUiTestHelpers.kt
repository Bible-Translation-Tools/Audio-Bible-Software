package org.bibletranslationtools.recorder2.e2e

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue

/**
 * Android e2e helpers.
 *
 * - Clicks/waits prefer UiAutomator (Compose Espresso idling hangs on continuous recomposition).
 * - Splash → main menu still needs the Compose test clock pumped: splash navigation runs in a
 *   [androidx.compose.runtime.LaunchedEffect], and UiAutomator alone does not advance that clock,
 *   so the UI stays on "Translation Recorder" forever.
 * - Splash on device can take up to ~1 minute; we poll that long, then tap as soon as home is up.
 */

internal fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.waitForMainMenuAfterSplash(
    timeoutMillis: Long = 60_000,
) {
    E2eLog.step("WAIT splash→main menu (pump Compose + uia, timeout=${timeoutMillis}ms)")
    val device = uiDevice()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    // Manual frames so splash LaunchedEffect (init + navigate) can complete under UI test.
    mainClock.autoAdvance = false

    while (SystemClock.uptimeMillis() < deadline) {
        runCatching { mainClock.advanceTimeByFrame() }
        if (device.hasObject(By.desc("Files")) && device.hasObject(By.desc("Record"))) {
            E2eLog.step("FOUND Files+Record (main menu) — proceeding with taps")
            return
        }
        // Let Rx init / real main looper make progress between frames.
        SystemClock.sleep(50)
    }
    assertTrue(
        "Timed out waiting for main menu after splash (Files + Record)",
        false
    )
}

internal fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.waitForContentDescription(
    label: String,
    timeoutMillis: Long = 180_000,
) {
    if (label == "Files" || label == "Record") {
        // After cold start / recreate we are often still on splash.
        waitForMainMenuAfterSplash(timeoutMillis)
        return
    }
    waitForContentDescriptionViaUiAutomator(label, timeoutMillis)
}

internal fun waitForText(
    text: String,
    timeoutMillis: Long = 60_000,
) {
    E2eLog.step("WAIT(uia) text=\"$text\" (timeout=${timeoutMillis}ms)")
    val device = uiDevice()
    val found = device.wait(Until.hasObject(By.text(text)), timeoutMillis)
    assertTrue("Timed out waiting for text: $text", found)
    E2eLog.step("FOUND(uia) text=\"$text\"")
}

internal fun clickContentDescription(label: String) {
    clickContentDescriptionViaUiAutomator(label)
}

internal fun clickText(text: String) {
    clickTextViaUiAutomator(text)
}

internal fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.assertDisplayedContentDescription(
    label: String,
) {
    E2eLog.step("ASSERT displayed contentDescription=\"$label\"")
    runCatching {
        onNodeWithContentDescription(label).assertIsDisplayed()
    }.onFailure {
        assertTrue(
            "Expected contentDescription \"$label\" on screen",
            uiDevice().hasObject(By.desc(label))
        )
    }
}

internal fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.assertDisplayedText(
    text: String,
) {
    E2eLog.step("ASSERT displayed text=\"$text\"")
    runCatching {
        onNodeWithText(text).assertIsDisplayed()
    }.onFailure {
        assertTrue(
            "Expected text \"$text\" on screen",
            uiDevice().hasObject(By.text(text))
        )
    }
}

internal fun waitForActiveWorkbook(timeoutMillis: Long = 60_000) {
    E2eLog.step("WAIT prefs.hasActiveWorkbook (timeout=${timeoutMillis}ms)")
    val prefs = org.koin.core.context.GlobalContext.get()
        .get<org.bibletranslationtools.shared.preferences.IAppPreferences>()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        val nav = kotlinx.coroutines.runBlocking {
            prefs.navState.first()
        }
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
        // Seeded project is Afar Genesis; titles come from workbook metadata.
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
    E2eLog.step("WAIT recorder transport or detect Project Management (timeout=${timeoutMillis}ms)")
    val device = uiDevice()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        if (device.hasObject(By.desc("Record transport"))) {
            E2eLog.step("FOUND(uia) contentDescription=\"Record transport\"")
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

internal fun waitForContentDescriptionViaUiAutomator(
    label: String,
    timeoutMillis: Long = 120_000,
) {
    E2eLog.step("WAIT(uia) contentDescription=\"$label\" (timeout=${timeoutMillis}ms)")
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val found = device.wait(Until.hasObject(By.desc(label)), timeoutMillis)
    assertTrue(
        "Timed out waiting for content description: $label",
        found
    )
    E2eLog.step("FOUND(uia) contentDescription=\"$label\"")
}

internal fun clickContentDescriptionViaUiAutomator(label: String) {
    E2eLog.step("CLICK(uia) contentDescription=\"$label\"")
    val device = uiDevice()
    assertTrue(
        "No node with contentDescription \"$label\" to click",
        device.wait(Until.hasObject(By.desc(label)), 5_000)
    )
    device.findObject(By.desc(label)).click()
}

internal fun clickTextViaUiAutomator(text: String) {
    E2eLog.step("CLICK(uia) text=\"$text\"")
    val device = uiDevice()
    assertTrue(
        "No node with text \"$text\" to click",
        device.wait(Until.hasObject(By.text(text)), 5_000)
    )
    device.findObject(By.text(text)).click()
}

internal fun uiDevice(): UiDevice =
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
