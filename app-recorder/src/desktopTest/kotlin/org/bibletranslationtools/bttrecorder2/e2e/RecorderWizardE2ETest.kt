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
            onAllNodesWithTag(TestTags.WIZARD_SCREEN).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("New Project").fetchSemanticsNodes().isNotEmpty()
        }

        waitUntil(timeoutMillis = 120_000) {
            onAllNodesWithTag("wizard-row-en_ulb").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithTag("wizard-row-en").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("English", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        when {
            onAllNodesWithTag("wizard-row-en_ulb").fetchSemanticsNodes().isNotEmpty() ->
                onNodeWithTag("wizard-row-en_ulb").performClick()
            onAllNodesWithTag("wizard-row-en").fetchSemanticsNodes().isNotEmpty() ->
                onNodeWithTag("wizard-row-en").performClick()
            else -> onNodeWithText("English", substring = true).performClick()
        }

        waitUntil(timeoutMillis = 120_000) {
            onAllNodesWithTag("wizard-row-aa").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("Afar", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (onAllNodesWithTag("wizard-row-aa").fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithTag("wizard-row-aa").performClick()
        } else {
            onNodeWithText("Afar", substring = true).performClick()
        }

        waitUntil(timeoutMillis = 120_000) {
            onAllNodesWithTag("wizard-row-gen").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("Genesis", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(TestTags.WIZARD_SCREEN).assertIsDisplayed()
        if (onAllNodesWithTag("wizard-row-gen").fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithTag("wizard-row-gen").assertIsDisplayed()
        } else {
            onNodeWithText("Genesis", substring = true).assertIsDisplayed()
        }
    }
}
