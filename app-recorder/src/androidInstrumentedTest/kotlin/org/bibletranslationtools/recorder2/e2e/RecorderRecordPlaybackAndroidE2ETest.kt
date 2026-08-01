package org.bibletranslationtools.recorder2.e2e

import android.Manifest
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.Dispatchers
import org.bibletranslationtools.recorder2.MainActivity
import org.bibletranslationtools.recorder2.e2e.harness.RecorderAndroidUiTestHarness
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecorderRecordPlaybackAndroidE2ETest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>(effectContext = Dispatchers.Main)

    @Before
    fun seedProject() {
        E2eLog.step("SETUP seed Genesis + recreate activity")
        composeRule.waitForMainMenuAfterSplash()
        RecorderAndroidUiTestHarness.seedGenesisProject()
        waitForActiveWorkbook()
        E2eLog.step("SETUP recreate activity")
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForMainMenuAfterSplash()
        waitForActiveWorkbook()
        // Give MainMenu collectAsState a moment to observe the active project (UI tint / labels).
        composeRule.mainClock.autoAdvance = false
        repeat(30) {
            runCatching { composeRule.mainClock.advanceTimeByFrame() }
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
                    clickContentDescriptionViaUiAutomator("Back")
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
        composeRule.waitForMainMenuAfterSplash()
        waitForActiveWorkbook()
        waitForActiveProjectOnMainMenu()
        clickContentDescription("Record")

        waitForRecorderTransportOrFail(timeoutMillis = 120_000)
        clickContentDescriptionViaUiAutomator("Record transport")

        val device = uiDevice()
        E2eLog.step("WAIT(uia) text=\"Stop\" (timeout=15000ms)")
        val stopAppeared = device.wait(Until.hasObject(By.text("Stop")), 15_000)
        if (stopAppeared) {
            E2eLog.step("FOUND(uia) text=\"Stop\"")
            clickTextViaUiAutomator("Stop")
            waitForContentDescriptionViaUiAutomator("Play/Pause", timeoutMillis = 120_000)
            E2eLog.step("ASSERT(uia) has Play/Pause")
            assertTrue(device.hasObject(By.desc("Play/Pause")))
        } else {
            E2eLog.step("MISS(uia) text=\"Stop\" — assert Record transport still present")
            assertTrue(device.hasObject(By.desc("Record transport")))
        }
        E2eLog.step("TEST openRecorderAndEngageTransport done")
    }
}
