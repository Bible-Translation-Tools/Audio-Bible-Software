package org.bibletranslationtools.bttrecorder2.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.bibletranslationtools.bttrecorder2.e2e.harness.RecorderUiTestHarness
import org.bibletranslationtools.bttrecorder2.ui.App
import org.bibletranslationtools.bttrecorder2.ui.TestTags
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class RecorderSmokeE2ETest {

    @BeforeTest
    fun setUp() {
        RecorderUiTestHarness.start()
    }

    @AfterTest
    fun tearDown() {
        RecorderUiTestHarness.stop()
    }

    @Test
    fun mainMenuVisibleAfterSplash() = runRecorderUiTest {
        setContent { App() }
        waitUntil(timeoutMillis = 180_000) {
            onAllNodesWithContentDescription("Files").fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithContentDescription("Record").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Files").assertIsDisplayed()
        onNodeWithContentDescription("Record").assertIsDisplayed()
    }

    @Test
    fun filesOpensProjectManagementAndSettings() = runRecorderUiTest {
        setContent { App() }
        waitUntil(timeoutMillis = 180_000) {
            onAllNodesWithContentDescription("Files").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Files").performClick()
        waitUntil(timeoutMillis = 30_000) {
            onAllNodesWithTag(TestTags.PROJECT_MANAGEMENT).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("Project Management").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("More options").performClick()
        waitUntil {
            onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Settings").performClick()
        // Require the settings screen tag — "Settings" text alone also matches the overflow item.
        waitUntil(timeoutMillis = 30_000) {
            onAllNodesWithTag(TestTags.SETTINGS_SCREEN).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(TestTags.SETTINGS_SCREEN).assertIsDisplayed()
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 30_000) {
            onAllNodesWithText("Project Management").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun recordWithoutActiveProjectGoesToProjectManagement() = runRecorderUiTest {
        setContent { App() }
        waitUntil(timeoutMillis = 180_000) {
            onAllNodesWithContentDescription("Record").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Record").performClick()
        waitUntil(timeoutMillis = 60_000) {
            onAllNodesWithText("Project Management").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Project Management").assertIsDisplayed()
    }
}
