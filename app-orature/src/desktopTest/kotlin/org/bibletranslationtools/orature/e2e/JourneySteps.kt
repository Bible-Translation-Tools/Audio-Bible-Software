package org.bibletranslationtools.orature.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText

/** A clickable row of a list (wizard tables, the book table) that shows [text]. */
internal fun listRow(text: String, substring: Boolean = false): SemanticsMatcher =
    hasText(text, substring = substring) and hasClickAction() and hasAnyAncestor(hasScrollAction())

/** A row of the wizard's version or edition table. Takes the first one offered. */
internal val firstWizardRow: SemanticsMatcher = hasClickAction() and hasAnyAncestor(hasScrollAction())

/**
 * The last steps of the project wizard depend on what is installed: a language with one version
 * and one edition creates the project straight away, otherwise the wizard asks which version and
 * edition to use. Takes the first offered each time, and returns once the book list shows [book].
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.finishWizard(guide: Walkthrough, book: String = "Genesis") {
    val version = hasText("Pick a Version (4 of 4)")
    val edition = hasText("Pick an Edition")
    val created = listRow(book)
    repeat(3) {
        waitUntil(timeoutMillis = 180_000) { isShowing(version) || isShowing(edition) || isShowing(created) }
        when {
            isShowing(version) -> {
                guide.snapshot("Pick which version of the text to work from")
                tap(firstWizardRow)
            }
            isShowing(edition) -> {
                guide.snapshot("Pick the edition (release) of that version")
                tap(firstWizardRow)
            }
            else -> return
        }
    }
}

/**
 * Lets real time pass while still drawing frames. The test clock does not advance on its own in
 * these tests, so a plain sleep would freeze the screen; the fake microphone and speakers run in
 * real time, so recording and playback need wall-clock time to make progress.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.letTimePass(millis: Long) {
    val until = System.currentTimeMillis() + millis
    while (System.currentTimeMillis() < until) {
        mainClock.advanceTimeByFrame()
        Thread.sleep(16)
    }
}
