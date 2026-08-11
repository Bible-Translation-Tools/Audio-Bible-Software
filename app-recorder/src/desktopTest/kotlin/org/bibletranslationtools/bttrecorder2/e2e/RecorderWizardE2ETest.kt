package org.bibletranslationtools.bttrecorder2.e2e

import androidx.compose.ui.test.ExperimentalTestApi
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
 * Full wizard create — parity with Android [CreateProjectWizardFlowTest]:
 * Files → New Project → English → Afar → Genesis → Project Management.
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
    fun createNewProjectViaWizard() = runRecorderUiTest {
        setContent { App() }
        waitUntil(timeoutMillis = 180_000) {
            onAllNodesWithContentDescription("Files").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithContentDescription("Files").performClick()
        waitUntil(timeoutMillis = 30_000) {
            onAllNodesWithText("Project Management").fetchSemanticsNodes().isNotEmpty()
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
            onAllNodesWithText("Choose Target Language").fetchSemanticsNodes().isNotEmpty()
        }

        searchAndClickResult(query = "aa", resultSubstring = "Afar", timeoutMillis = 120_000)
        waitUntil(timeoutMillis = 120_000) {
            onAllNodesWithText("Choose a Book").fetchSemanticsNodes().isNotEmpty()
        }

        searchAndClickResult(query = "gen", resultSubstring = "Genesis", timeoutMillis = 120_000)

        waitUntil(timeoutMillis = 180_000) {
            onAllNodesWithText("Project Management").fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 60_000) {
            onAllNodesWithText("Genesis", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
