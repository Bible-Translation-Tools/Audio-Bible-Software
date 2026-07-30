package org.bibletranslationtools.bttrecorder2.e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
