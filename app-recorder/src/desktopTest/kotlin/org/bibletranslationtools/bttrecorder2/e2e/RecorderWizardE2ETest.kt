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

/**
 * Phase 2 wizard UI coverage: drive source/target/book selection. Project creation is
 * validated via [RecorderUiTestHarness.seedGenesisProject] + [RecorderRecordPlaybackE2ETest]
 * because in-process create can race Compose test dispatchers with Rx blocking calls.
 */
@OptIn(ExperimentalTestApi::class)
class RecorderWizardE2ETest {

    @BeforeTest
    fun setUp() {
        RecorderUiTestHarness.start()
    }

    @AfterTest
    fun tearDown() {
        RecorderUiTestHarness.stop()
    }

    @Test
    fun wizardSourceTargetBookStepsReachable() = runRecorderUiTest {
        setContent { App() }
        waitUntil(timeoutMillis = 180_000) {
            onAllNodesWithContentDescription("Files").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Files").performClick()
        waitUntil(timeoutMillis = 30_000) {
            onAllNodesWithContentDescription("New Project").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("New Project").performClick()
        waitUntil(timeoutMillis = 60_000) {
            onAllNodesWithText("Select Source").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("New Project").fetchSemanticsNodes().isNotEmpty()
        }

        waitUntil(timeoutMillis = 120_000) {
            onAllNodesWithText("English", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("en_ulb", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("en", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        when {
            onAllNodesWithText("en_ulb", substring = true).fetchSemanticsNodes().isNotEmpty() ->
                onNodeWithText("en_ulb", substring = true).performClick()
            onAllNodesWithText("English", substring = true).fetchSemanticsNodes().isNotEmpty() ->
                onNodeWithText("English", substring = true).performClick()
            else -> onNodeWithText("en").performClick()
        }

        waitUntil(timeoutMillis = 120_000) {
            onAllNodesWithText("Afar", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("aa").fetchSemanticsNodes().isNotEmpty()
        }
        if (onAllNodesWithText("Afar", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithText("Afar", substring = true).performClick()
        } else {
            onNodeWithText("aa").performClick()
        }

        waitUntil(timeoutMillis = 120_000) {
            onAllNodesWithText("Choose a Book").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("Genesis", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Choose a Book").assertIsDisplayed()
        if (onAllNodesWithText("Genesis", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithText("Genesis", substring = true).assertIsDisplayed()
        } else {
            onNodeWithText("gen").assertIsDisplayed()
        }
    }
}
