package org.bibletranslationtools.recorder2.e2e

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.recorder2.MainActivity
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class RecorderSmokeAndroidE2ETest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    // Real Main so splash LaunchedEffect / Rx resume are not stuck on the test dispatcher alone.
    val composeRule = createAndroidComposeRule<MainActivity>(effectContext = Dispatchers.Main)

    @Before
    fun clearActiveWorkbook() {
        E2eLog.step("SETUP clear active workbook + recreate")
        composeRule.waitForMainMenuAfterSplash()
        runBlocking {
            GlobalContext.get().get<IAppPreferences>().clearActiveWorkbook()
        }
        E2eLog.step("SETUP recreate activity")
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForMainMenuAfterSplash()
        E2eLog.step("SETUP done")
    }

    @Test
    fun mainMenuVisibleAfterSplash() {
        E2eLog.step("TEST mainMenuVisibleAfterSplash start")
        composeRule.waitForMainMenuAfterSplash()
        composeRule.assertDisplayedContentDescription("Files")
        composeRule.assertDisplayedContentDescription("Record")
        E2eLog.step("TEST mainMenuVisibleAfterSplash done")
    }

    @Test
    fun filesOpensProjectManagementAndSettings() {
        E2eLog.step("TEST filesOpensProjectManagementAndSettings start")
        composeRule.waitForMainMenuAfterSplash()
        clickContentDescription("Files")
        waitForText("Project Management", timeoutMillis = 30_000)

        clickContentDescription("More options")
        waitForText("Settings", timeoutMillis = 15_000)
        clickText("Settings")
        waitForText("Audio", timeoutMillis = 30_000)
        composeRule.assertDisplayedText("Audio")

        clickContentDescription("Back")
        waitForText("Project Management", timeoutMillis = 30_000)
        E2eLog.step("TEST filesOpensProjectManagementAndSettings done")
    }

    @Test
    fun recordWithoutActiveProjectGoesToProjectManagement() {
        E2eLog.step("TEST recordWithoutActiveProjectGoesToProjectManagement start")
        composeRule.waitForMainMenuAfterSplash()
        clickContentDescription("Record")
        waitForText("Project Management", timeoutMillis = 60_000)
        composeRule.assertDisplayedText("Project Management")
        E2eLog.step("TEST recordWithoutActiveProjectGoesToProjectManagement done")
    }
}
