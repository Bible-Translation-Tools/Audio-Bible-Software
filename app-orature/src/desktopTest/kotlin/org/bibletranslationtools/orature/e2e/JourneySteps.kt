package org.bibletranslationtools.orature.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performTextInput

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
internal fun ComposeUiTest.finishWizard(guide: Walkthrough?, book: String = "Genesis") {
    val version = hasText("Pick a Version (4 of 4)")
    val edition = hasText("Pick an Edition")
    val created = listRow(book)
    repeat(3) {
        waitUntil(timeoutMillis = 180_000) { isShowing(version) || isShowing(edition) || isShowing(created) }
        when {
            isShowing(version) -> {
                guide?.snapshot("Pick which version of the text to work from")
                tap(firstWizardRow)
            }
            isShowing(edition) -> {
                guide?.snapshot("Pick the edition (release) of that version")
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

/** The Save button narration shows once the last line is recorded, and while re-recording. */
internal val saveButton: SemanticsMatcher = hasText("Save") and hasClickAction()

/**
 * From Home: creates a Narration project for English Genesis and narrates chapter 1, about a
 * second per line, with no screenshots. Ends on the narration screen with every line recorded.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.narrateGenesisOne() {
    waitFor(hasContentDescription("New Project"), timeoutMillis = 180_000)
    tap(hasContentDescription("New Project"))
    tap(hasText("Narration") and hasClickAction())
    waitFor(hasText("Pick a Source Language (2 of 4)"))
    onNode(hasSetTextAction()).performTextInput("English")
    tap(listRow("English"))
    waitUntil(timeoutMillis = 180_000) {
        isShowing(listRow("Genesis")) || isShowing(hasText("Pick a Version (4 of 4)")) || isShowing(hasText("Pick an Edition"))
    }
    repeat(2) { if (!isShowing(listRow("Genesis"))) { tap(firstWizardRow); letTimePass(500) } }
    waitFor(listRow("Genesis"), timeoutMillis = 180_000)

    tap(listRow("Genesis"))
    val record = hasText("Record") and hasClickAction() and isEnabled()
    waitFor(record, timeoutMillis = 120_000)
    tap(record)
    val next = hasText("Next") and hasClickAction()
    waitFor(next)
    // Next after each line until the last line's button says Save. Let a frame draw before
    // checking, or the check sees the screen from before the last tap.
    for (line in 1..60) {
        letTimePass(1_000) // about a second per line, so the markers are far enough apart to grab
        if (isShowing(saveButton)) break
        tap(next)
    }
    tap(saveButton)
    waitUntil(timeoutMillis = 60_000) { !isShowing(saveButton) && !isShowing(next) }
    waitFor(hasText("Re-Record") and hasClickAction(), timeoutMillis = 60_000)
}

/**
 * From Home: creates an Oral Translation project from English into Spanish (es) and opens
 * Genesis 1 on its first step, Consume. Needs source audio for Genesis 1 imported first.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.createGenesisTranslation(guide: Walkthrough? = null) {
    waitFor(hasContentDescription("New Project"), timeoutMillis = 180_000)
    tap(hasContentDescription("New Project"))
    tap(hasText("Oral Translation") and hasClickAction())
    waitFor(hasText("Pick a Source Language (2 of 4)"))
    onNode(hasSetTextAction()).performTextInput("English")
    tap(listRow("English"))
    waitFor(hasText("Pick a Target Language (3 of 4)"), timeoutMillis = 60_000)
    onNode(hasSetTextAction()).performTextInput("Spanish")
    tap(listRow("es"))
    finishWizard(guide)
    tap(listRow("Genesis"))
    waitFor(hasText("Play Source") and hasClickAction(), timeoutMillis = 120_000)
}
