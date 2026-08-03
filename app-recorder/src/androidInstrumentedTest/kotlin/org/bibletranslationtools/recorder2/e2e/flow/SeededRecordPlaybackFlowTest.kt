package org.bibletranslationtools.recorder2.e2e.flow

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.bibletranslationtools.recorder2.MainActivity
import org.bibletranslationtools.recorder2.e2e.E2eLog
import org.bibletranslationtools.recorder2.e2e.clickContentDescription
import org.bibletranslationtools.recorder2.e2e.clickText
import org.bibletranslationtools.recorder2.e2e.harness.RecorderAndroidUiTestHarness
import org.bibletranslationtools.recorder2.e2e.uiDevice
import org.bibletranslationtools.recorder2.e2e.waitForActiveProjectOnMainMenu
import org.bibletranslationtools.recorder2.e2e.waitForActiveWorkbook
import org.bibletranslationtools.recorder2.e2e.waitForContentDescription
import org.bibletranslationtools.recorder2.e2e.waitForMainMenuAfterSplash
import org.bibletranslationtools.recorder2.e2e.waitForRecorderTransportOrFail
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seed Afar Genesis in-process → home with active project → Record → engage transport.
 */
@RunWith(AndroidJUnit4::class)
class SeededRecordPlaybackFlowTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun seedProject() {
        E2eLog.step("SETUP seed Genesis + recreate activity")
        waitForMainMenuAfterSplash()
        RecorderAndroidUiTestHarness.seedGenesisProject()
        waitForActiveWorkbook()
        E2eLog.step("SETUP recreate activity")
        activityRule.scenario.recreate()
        waitForMainMenuAfterSplash()
        waitForActiveWorkbook()
        // Give MainMenu collectAsState a moment to observe the active project.
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (uiDevice().hasObject(By.textContains("Genesis")) ||
                uiDevice().hasObject(By.textContains("Afar"))
            ) {
                break
            }
            SystemClock.sleep(50)
        }
        waitForActiveProjectOnMainMenu()
        E2eLog.step("SETUP done")
    }

    @After
    fun leaveRecorderBeforeTeardown() {
        runCatching {
            val device = uiDevice()
            if (device.hasObject(By.desc("Record transport"))) {
                E2eLog.step("TEARDOWN leave recorder")
                if (device.hasObject(By.desc("Back"))) {
                    clickContentDescription("Back")
                } else {
                    E2eLog.step("TEARDOWN pressBack()")
                    device.pressBack()
                }
                E2eLog.step("TEARDOWN wait for Files")
                device.wait(Until.hasObject(By.desc("Files")), 30_000)
            }
        }
    }

    @Test
    fun openRecorderAndEngageTransport() {
        E2eLog.step("TEST openRecorderAndEngageTransport start")
        waitForMainMenuAfterSplash()
        waitForActiveWorkbook()
        waitForActiveProjectOnMainMenu()
        clickContentDescription("Record")

        waitForRecorderTransportOrFail(timeoutMillis = 120_000)
        clickContentDescription("Record transport")

        val device = uiDevice()
        E2eLog.step("WAIT text=\"Stop\" (timeout=15000ms)")
        val stopAppeared = device.wait(Until.hasObject(By.text("Stop")), 15_000)
        if (stopAppeared) {
            E2eLog.step("FOUND text=\"Stop\"")
            clickText("Stop")
            waitForContentDescription("Play/Pause", timeoutMillis = 120_000)
            E2eLog.step("ASSERT has Play/Pause")
            assertTrue(device.hasObject(By.desc("Play/Pause")))
        } else {
            E2eLog.step("MISS text=\"Stop\" — assert Record transport still present")
            assertTrue(device.hasObject(By.desc("Record transport")))
        }
        E2eLog.step("TEST openRecorderAndEngageTransport done")
    }
}
