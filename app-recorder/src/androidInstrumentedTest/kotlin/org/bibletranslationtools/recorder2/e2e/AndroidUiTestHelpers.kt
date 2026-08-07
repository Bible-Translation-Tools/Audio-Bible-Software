package org.bibletranslationtools.recorder2.e2e

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

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
    val device = uiDevice()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        if (device.hasObject(By.desc("Files")) && device.hasObject(By.desc("Record"))) {
            E2eLog.step("FOUND Files+Record (main menu)")
            return
        }
        SystemClock.sleep(50)
    }
    captureAndUploadTimeoutScreenshot("main-menu-timeout")
    assertTrue("Timed out waiting for main menu after splash (Files + Record)", false)
}

internal fun waitForText(text: String, timeoutMillis: Long = 60_000) {
    E2eLog.step("WAIT text=\"$text\" (timeout=${timeoutMillis}ms)")
    val found = uiDevice().wait(Until.hasObject(By.text(text)), timeoutMillis)
    if (!found) {
        captureAndUploadTimeoutScreenshot("wait-text-${text.take(40)}")
    }
    assertTrue("Timed out waiting for text: $text", found)
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
    clickNodeCenter(By.desc(label))
}

internal fun clickText(text: String) {
    E2eLog.step("CLICK text=\"$text\"")
    val device = uiDevice()
    assertTrue(
        "No node with text \"$text\" to click",
        device.wait(Until.hasObject(By.text(text)), 5_000)
    )
    clickNodeCenter(By.text(text))
}

internal fun clickTextContains(substring: String) {
    E2eLog.step("CLICK textContains=\"$substring\"")
    val device = uiDevice()
    assertTrue(
        "No node with text containing \"$substring\" to click",
        device.wait(Until.hasObject(By.textContains(substring)), 5_000)
    )
    clickNodeCenter(By.textContains(substring))
}

/**
 * Click [contentDescription] and retry until [text] appears. Needed on API 34 ATD where
 * Compose clickables are often found by UiAutomator but the first gesture misses onClick.
 */
internal fun clickContentDescriptionUntilText(
    contentDescription: String,
    text: String,
    timeoutMillis: Long = 60_000,
) {
    E2eLog.step(
        "CLICK contentDescription=\"$contentDescription\" until text=\"$text\" " +
            "(timeout=${timeoutMillis}ms)"
    )
    val device = uiDevice()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    var attempt = 0
    while (SystemClock.uptimeMillis() < deadline) {
        if (device.hasObject(By.text(text))) {
            E2eLog.step("FOUND text=\"$text\"")
            return
        }
        if (device.hasObject(By.desc(contentDescription))) {
            attempt++
            E2eLog.step("CLICK contentDescription=\"$contentDescription\" (attempt $attempt)")
            clickNodeCenter(By.desc(contentDescription))
            if (device.wait(Until.hasObject(By.text(text)), 3_000)) {
                E2eLog.step("FOUND text=\"$text\"")
                return
            }
        }
        SystemClock.sleep(200)
    }
    captureAndUploadTimeoutScreenshot("click-$contentDescription-until-$text")
    assertTrue(
        "Timed out waiting for text: $text (after clicking contentDescription \"$contentDescription\")",
        false
    )
}

/**
 * Saves a PNG under `/data/local/tmp/e2e-screenshots` (adb-pullable) and POSTs it to
 * tmpfiles.org so the view URL appears in the Gradle / CI log.
 */
internal fun captureAndUploadTimeoutScreenshot(label: String) {
    val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
    val dir = File("/data/local/tmp/e2e-screenshots").also { it.mkdirs() }
    val file = File(dir, "${safe}_${System.currentTimeMillis()}.png")
    val ok = runCatching { uiDevice().takeScreenshot(file) }.getOrDefault(false)
    E2eLog.step(
        "SCREENSHOT label=$safe ok=$ok path=${file.absolutePath} " +
            "exists=${file.exists()} size=${if (file.exists()) file.length() else 0}"
    )
    if (!ok || !file.exists() || file.length() == 0L) return
    runCatching { uploadScreenshotToTmpfiles(file) }
        .onFailure { E2eLog.step("SCREENSHOT upload failed: ${it.message}") }
}

private fun uploadScreenshotToTmpfiles(file: File) {
    val boundary = "----RecorderE2E${System.currentTimeMillis()}"
    val conn = (URI("https://tmpfiles.org/api/v1/upload").toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 30_000
        readTimeout = 60_000
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    }
    DataOutputStream(conn.outputStream).use { out ->
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"expire\"\r\n\r\n")
        out.writeBytes("86400\r\n")
        out.writeBytes("--$boundary\r\n")
        out.writeBytes(
            "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n"
        )
        out.writeBytes("Content-Type: image/png\r\n\r\n")
        out.flush()
        file.inputStream().use { it.copyTo(out) }
        out.writeBytes("\r\n--$boundary--\r\n")
        out.flush()
    }
    val code = conn.responseCode
    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()
        ?.use { it.readText() }
        .orEmpty()
    conn.disconnect()
    E2eLog.step("SCREENSHOT tmpfiles HTTP $code body=$body")
    // Response url is a view page; raw bytes need /dl/ inserted after the host.
    Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)?.let { viewUrl ->
        val dlHint = viewUrl.replace("://tmpfiles.org/", "://tmpfiles.org/dl/")
        E2eLog.step("SCREENSHOT view=$viewUrl")
        E2eLog.step("SCREENSHOT dl_hint=$dlHint")
    }
}

private fun clickNodeCenter(selector: BySelector) {
    val node = uiDevice().findObject(selector)
    val bounds = node.visibleBounds
    uiDevice().click(bounds.centerX(), bounds.centerY())
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
 *
 * UiAutomator clicks on Compose IconButtons are flaky on API 34 ATD (node found but
 * onClick never runs). Retry until "Close search" appears rather than failing on one miss.
 */
internal fun searchAndClickResult(
    query: String,
    resultSubstring: String,
    timeoutMillis: Long = 60_000,
) {
    E2eLog.step("SEARCH query=\"$query\" then click textContains=\"$resultSubstring\"")
    openWizardSearch()
    SystemClock.sleep(300)
    InstrumentationRegistry.getInstrumentation().sendStringSync(query)
    waitForTextContains(resultSubstring, timeoutMillis)
    clickTextContains(resultSubstring)
}

internal fun openWizardSearch(timeoutMillis: Long = 30_000) {
    val device = uiDevice()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    var attempt = 0
    while (SystemClock.uptimeMillis() < deadline) {
        if (device.hasObject(By.desc("Close search"))) {
            E2eLog.step("FOUND contentDescription=\"Close search\"")
            return
        }
        if (device.hasObject(By.desc("Search"))) {
            attempt++
            E2eLog.step("CLICK contentDescription=\"Search\" (attempt $attempt)")
            // Prefer bounds center: UiObject2.click() sometimes misses Compose hit targets on ATD.
            val search = device.findObject(By.desc("Search"))
            val bounds = search.visibleBounds
            device.click(bounds.centerX(), bounds.centerY())
            if (device.wait(Until.hasObject(By.desc("Close search")), 3_000)) {
                E2eLog.step("FOUND contentDescription=\"Close search\"")
                return
            }
        }
        SystemClock.sleep(200)
    }
    assertTrue("Wizard search did not open (Close search not found)", false)
}

internal fun uiDevice(): UiDevice =
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
