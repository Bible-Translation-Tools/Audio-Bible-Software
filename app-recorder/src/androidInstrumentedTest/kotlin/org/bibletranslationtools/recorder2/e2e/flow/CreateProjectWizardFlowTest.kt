package org.bibletranslationtools.recorder2.e2e.flow

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.recorder2.MainActivity
import org.bibletranslationtools.recorder2.e2e.E2eLog
import org.bibletranslationtools.recorder2.e2e.clickContentDescription
import org.bibletranslationtools.recorder2.e2e.clickTextContains
import org.bibletranslationtools.recorder2.e2e.searchAndClickResult
import org.bibletranslationtools.recorder2.e2e.uiDevice
import org.bibletranslationtools.recorder2.e2e.waitForMainMenuAfterSplash
import org.bibletranslationtools.recorder2.e2e.waitForText
import org.bibletranslationtools.recorder2.e2e.waitForTextContains
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Launch → splash → home → Files → New Project → English → Afar → Genesis → Project Management.
 *
 * Uses [ActivityScenarioRule] (not ComposeTestRule) so navigation matches a normal install.
 */
@RunWith(AndroidJUnit4::class)
class CreateProjectWizardFlowTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun clearActiveWorkbook() {
        E2eLog.step("SETUP clear active workbook + recreate")
        waitForMainMenuAfterSplash(240_000)
        runBlocking {
            GlobalContext.get().get<IAppPreferences>().clearActiveWorkbook()
        }
        activityRule.scenario.recreate()
        waitForMainMenuAfterSplash()
        E2eLog.step("SETUP done")
    }

    @Test
    fun createNewProjectViaWizard() {
        E2eLog.step("TEST createNewProjectViaWizard start")
        waitForMainMenuAfterSplash(240_000)

        clickContentDescription("Files")
        waitForText("Project Management", timeoutMillis = 30_000)

        clickContentDescription("New Project")
        waitForWizardSourceStep()

        selectEnglishSource()
        waitForText("Choose Target Language", timeoutMillis = 120_000)

        searchAndClickResult(query = "aa", resultSubstring = "Afar", timeoutMillis = 120_000)
        waitForText("Choose a Book", timeoutMillis = 120_000)

        searchAndClickResult(query = "gen", resultSubstring = "Genesis", timeoutMillis = 120_000)

        waitForText("Project Management", timeoutMillis = 180_000)
        waitForTextContains("Genesis", timeoutMillis = 60_000)
        E2eLog.step("TEST createNewProjectViaWizard done")
    }

    private fun waitForWizardSourceStep(timeoutMillis: Long = 60_000) {
        E2eLog.step("WAIT wizard source step (timeout=${timeoutMillis}ms)")
        val device = uiDevice()
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.hasObject(By.text("Select Source")) ||
                device.hasObject(By.text("New Project"))
            ) {
                E2eLog.step("FOUND wizard source / New Project title")
                return
            }
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for New Project wizard", false)
    }

    private fun selectEnglishSource() {
        E2eLog.step("SELECT English / en_ulb source")
        val device = uiDevice()
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            when {
                device.hasObject(By.textContains("en_ulb")) -> {
                    clickTextContains("en_ulb")
                    return
                }
                device.hasObject(By.textContains("English")) -> {
                    clickTextContains("English")
                    return
                }
            }
            SystemClock.sleep(100)
        }
        searchAndClickResult(query = "en", resultSubstring = "English", timeoutMillis = 120_000)
    }
}
