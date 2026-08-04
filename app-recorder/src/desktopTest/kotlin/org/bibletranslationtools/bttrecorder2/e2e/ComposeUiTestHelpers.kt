package org.bibletranslationtools.bttrecorder2.e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.minutes

/**
 * Desktop Compose UI e2e runner.
 *
 * - [Dispatchers.Main] for effects so splash / LaunchedEffect run (not queued forever on
 *   StandardTestDispatcher).
 * - [mainClock.autoAdvance] = false so continuous invalidations (waveforms, loading) do not make
 *   waitForIdle hang; [waitUntil] still advances frames via the test clock.
 * - Clears content before scene close to avoid NavBackStackEntry lifecycle teardown races.
 */
@OptIn(ExperimentalTestApi::class)
internal fun runRecorderUiTest(block: suspend ComposeUiTest.() -> Unit) {
    try {
        runComposeUiTest(
            effectContext = Dispatchers.Main,
            runTestContext = EmptyCoroutineContext,
            testTimeout = 15.minutes,
        ) {
            mainClock.autoAdvance = false
            try {
                block()
            } finally {
                runCatching { setContent { Box {} } }
            }
        }
    } catch (e: IllegalStateException) {
        if (e.message?.contains("DESTROYED") != true) throw e
    }
}

/**
 * Opens the wizard search field, types [query], then clicks a row matching [resultSubstring].
 * Parity with Android [org.bibletranslationtools.recorder2.e2e.searchAndClickResult].
 */
@OptIn(ExperimentalTestApi::class)
internal suspend fun ComposeUiTest.searchAndClickResult(
    query: String,
    resultSubstring: String,
    timeoutMillis: Long = 60_000,
) {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithContentDescription("Search").performClick()
    waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
    }
    onNode(hasSetTextAction()).performTextInput(query)
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithText(resultSubstring, substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText(resultSubstring, substring = true).performClick()
}
