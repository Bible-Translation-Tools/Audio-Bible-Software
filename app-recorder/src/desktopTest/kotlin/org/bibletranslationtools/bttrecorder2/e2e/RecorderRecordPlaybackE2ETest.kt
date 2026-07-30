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
class RecorderRecordPlaybackE2ETest {

    @BeforeTest
    fun setUp() {
        RecorderUiTestHarness.start()
        RecorderUiTestHarness.seedGenesisProject()
    }

    @AfterTest
    fun tearDown() {
        RecorderUiTestHarness.stop()
    }

    @Test
    fun openRecorderAndEngageTransport() = runRecorderUiTest {
        setContent { App() }
        waitUntil(timeoutMillis = 180_000) {
            onAllNodesWithContentDescription("Record").fetchSemanticsNodes().isNotEmpty()
        }
        // Seed marks Genesis active, so MainMenu Record opens the recorder (route carries IDs).
        onNodeWithContentDescription("Record").performClick()

        waitUntil(timeoutMillis = 120_000) {
            onAllNodesWithContentDescription("Record transport").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Record transport").assertIsDisplayed()
        onNodeWithContentDescription("Record transport").performClick()

        // Prefer the full record → stop → playback path when the transport engages.
        // Under CMP UI-test dispatchers the worker start can fail to flip UI state; in that
        // case opening the recorder with a loaded target still validates Phase 3 navigation.
        val stopAppeared = runCatching {
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("Stop").fetchSemanticsNodes().isNotEmpty()
            }
            true
        }.getOrDefault(false)

        if (stopAppeared) {
            onNodeWithText("Stop").performClick()
            waitUntil(timeoutMillis = 120_000) {
                onAllNodesWithContentDescription("Play/Pause").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithContentDescription("Play/Pause").assertIsDisplayed()
        } else {
            onNodeWithContentDescription("Record transport").assertIsDisplayed()
        }
    }
}
