package org.bibletranslationtools.bttrecorder2.e2e

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Canonical CMP 1.11 desktop UI test (JVM Windows).
 *
 * Use `androidx.compose.ui.test.v2.runComposeUiTest` — the non-v2 APIs are deprecated.
 * Do not pass `Dispatchers.Main` as `effectContext` (Swing EDT + invokeAndWait deadlock risk).
 * Optional: `-Dskiko.renderApi=SOFTWARE` (CI/GPU). Do not set `java.awt.headless=true`.
 */
@OptIn(ExperimentalTestApi::class)
class MinimalComposeUiTest {
    @Test
    fun helloWorldRenders() = runComposeUiTest(testTimeout = 30.seconds) {
        setContent { Text("hello-e2e") }
        onNodeWithText("hello-e2e").assertIsDisplayed()
    }
}
