package org.bibletranslationtools.bttrecorder2.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
 * A gateway source other than English can be chosen in the wizard. Only English is imported at
 * startup; the others are sideloaded from their bundled zip when picked, and that path used to read
 * the zips through the classloader, where Compose resources are not, so it failed with an NPE and
 * left the wizard sitting on Select Source.
 */
@OptIn(ExperimentalTestApi::class)
class RecorderBundledSourceE2ETest {

    @BeforeTest
    fun setUp() {
        RecorderUiTestHarness.start()
    }

    @AfterTest
    fun tearDown() {
        RecorderUiTestHarness.stop()
    }

    @Test
    fun choosingANonEnglishSourceSideloadsIt() = runRecorderUiTest {
        setContent { App() }
        waitUntil(timeoutMillis = 180_000) {
            onAllNodesWithContentDescription("Files").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Files").performClick()
        // A FAB labelled only for accessibility, or a button with visible text, depending on the screen's version.
        val newProject = (hasText("New Project") or hasContentDescription("New Project")) and hasClickAction()
        waitUntil(timeoutMillis = 30_000) { onAllNodes(newProject).fetchSemanticsNodes().isNotEmpty() }
        onNode(newProject).performClick()

        waitUntil(timeoutMillis = 120_000) { onAllNodesWithText("Français").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Français").performClick()

        waitUntil(timeoutMillis = 120_000) {
            onAllNodesWithText("Choose Target Language").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
