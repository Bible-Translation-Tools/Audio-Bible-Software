package org.bibletranslationtools.bttrecorder2.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.bibletranslationtools.bttrecorder2.e2e.harness.RecorderUiTestHarness
import org.bibletranslationtools.bttrecorder2.ui.App
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
            onAllNodesWithText("Project Management").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("More options").performClick()
        waitUntil {
            onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Settings").performClick()
        // "Settings" also matches the overflow item; the Audio section is settings-only.
        waitUntil(timeoutMillis = 30_000) {
            onAllNodesWithText("Audio").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Audio").assertIsDisplayed()
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
