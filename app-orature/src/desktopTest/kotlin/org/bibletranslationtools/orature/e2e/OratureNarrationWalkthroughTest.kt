package org.bibletranslationtools.orature.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performTextInput
import org.bibletranslationtools.orature.e2e.harness.OratureUiTestHarness
import org.bibletranslationtools.orature.ui.OratureApp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The core narration journey, start to finish: create a narration project, record all of
 * Genesis 1, come back to Home, and set up a backup of that chapter.
 *
 * It is also a guide to the app. Each step saves a captioned screenshot, so after a run
 * `app-orature/build/walkthroughs/narration-journey/README.md` shows the journey screen by
 * screen. Step captions say what a user does and why, not how the test finds the button.
 *
 * The microphone is a fake that "hears" a tone, so this runs anywhere, CI included.
 * Stops at the export dialog: pressing Export Book opens the operating system's save dialog,
 * which a Compose test cannot operate.
 */
@OptIn(ExperimentalTestApi::class)
class OratureNarrationWalkthroughTest {

    @BeforeTest
    fun setUp() {
        OratureUiTestHarness.start()
    }

    @AfterTest
    fun tearDown() {
        OratureUiTestHarness.stop()
    }

    @Test
    fun narrateGenesisOneAndSetUpABackup() = runOratureUiTest {
        setContent { OratureApp(startWithSplash = false) }
        val guide = Walkthrough(
            test = this,
            name = "narration-journey",
            title = "Narrating a book in Orature",
            intro = "Narration means reading an existing written translation aloud, verse by verse. " +
                "This walks through creating a narration project, recording Genesis 1, and " +
                "choosing what to export."
        )
        guide.run { journey(this@runOratureUiTest, guide) }
    }

    private suspend fun journey(test: ComposeUiTest, guide: Walkthrough) = with(test) {

        // ── Create the project ──────────────────────────────────────────────────────────
        waitFor(hasContentDescription("New Project"), timeoutMillis = 180_000)
        guide.snapshot("Home. Projects are listed on the left; with none yet, Orature asks you to create one")

        guide.step("Press + (New Project). The project wizard opens in place of the book list") {
            tap(hasContentDescription("New Project"))
            waitFor(hasText("Pick a Project Type (1 of 4)"))
        }

        guide.step("Pick Narration: recording an existing written translation in its own language") {
            tap(hasText("Narration") and hasClickAction())
            waitFor(hasText("Pick a Source Language (2 of 4)"))
        }

        guide.step("Search for the language of the text you will read aloud") {
            onNode(hasSetTextAction()).performTextInput("English")
            waitFor(listRow("English"))
        }

        tap(listRow("English"))
        finishWizard(guide)

        waitFor(listRow("Genesis"), timeoutMillis = 180_000)
        guide.snapshot("The project is created. Its books are listed in the middle, with each book's progress")

        // ── Narrate ─────────────────────────────────────────────────────────────────────
        guide.step("Open Genesis. Chapter 1 opens with every line to read: the book title, the chapter number, then each verse") {
            tap(listRow("Genesis"))
            waitFor(hasText("Narrating: Genesis", substring = true), timeoutMillis = 120_000)
            waitFor(recordButton, timeoutMillis = 120_000)
        }

        guide.step("Press Record and read the book title aloud. The waveform at the top fills as you speak") {
            tap(recordButton)
            waitFor(nextButton)
            keepRecordingFor(millis = 1_500)
        }

        guide.step("Press Next to move on: the recording continues, and a marker separates each line (here, the chapter number)") {
            tap(nextButton)
            keepRecordingFor(millis = 1_500)
        }

        guide.step("Keep pressing Next as you finish each line. Now reading verse 1") {
            tap(nextButton)
            keepRecordingFor(millis = 1_500)
        }

        guide.step("Press Pause to take a break. The verse so far is kept") {
            tap(hasText("Pause") and hasClickAction())
            waitFor(hasText("Resume") and hasClickAction())
        }

        guide.step("Press Resume to carry on recording where you stopped") {
            tap(hasText("Resume") and hasClickAction())
            waitFor(nextButton)
            keepRecordingFor(millis = 1_000)
        }

        // Read the rest of the chapter: Next after each verse, until the last verse's
        // button says Save instead.
        val saveButton = hasText("Save") and hasClickAction()
        repeat(60) {
            if (isShowing(saveButton)) return@repeat
            tap(nextButton)
            keepRecordingFor(millis = 200)
        }
        guide.snapshot("On the last verse, Next becomes Save")

        guide.step("Press Save. The chapter is complete: every line has a recording, and each can be played or re-recorded") {
            tap(saveButton)
            waitUntil(timeoutMillis = 60_000) { !isShowing(saveButton) && !isShowing(nextButton) }
            waitFor(hasText("Re-Record") and hasClickAction(), timeoutMillis = 60_000)
        }

        // ── Export ──────────────────────────────────────────────────────────────────────
        guide.step("Go back (arrow, top left) to Home") {
            tap(hasContentDescription("Go Back"))
            waitFor(listRow("Genesis"), timeoutMillis = 60_000)
        }

        guide.step("Search the book list to find a book quickly") {
            onNode(hasSetTextAction()).performTextInput("Genesis")
            waitUntil(timeoutMillis = 30_000) { onAllNodes(listRow("Exodus")).fetchSemanticsNodes().isEmpty() }
        }

        guide.step("Each book's ⋮ menu has Open Book, Backup, Export and Delete Book") {
            tap(hasContentDescription("Options") and hasAnyAncestor(hasScrollAction()))
            waitFor(hasText("Export..."))
        }

        guide.step("Export... lets you choose what to export and which chapters") {
            tap(hasText("Export..."))
            waitFor(hasText("Genesis Export"))
        }

        guide.step("Pick Backup: a project file that Orature can import, for safekeeping or to move to another computer") {
            tap(hasText("Backup") and hasClickAction())
        }

        guide.step("Chapters that can go in a backup (any with a recording) are ticked for you. Only chapter 1 is recorded") {
            // The chapter list loads after the dialog opens; a chapter becomes tickable once it has.
            val tickable = isToggleable() and isEnabled() and hasAnyAncestor(hasScrollAction())
            waitFor(tickable, timeoutMillis = 30_000)
            waitFor(isToggleable() and isOn() and hasAnyAncestor(hasScrollAction()), timeoutMillis = 30_000)
            waitFor(hasText("Export Book") and isEnabled(), timeoutMillis = 30_000)
        }
        // Export Book opens the operating system's save dialog, which a Compose test cannot
        // drive, so the journey ends here.
    }

    private val nextButton = hasText("Next") and hasClickAction()

    /** The first verse's Record button, which is the only enabled one until it is recorded. */
    private val recordButton = hasText("Record") and hasClickAction() and isEnabled()

    /** Lets the fake microphone run in real time (see [letTimePass]). */
    private fun ComposeUiTest.keepRecordingFor(millis: Long) = letTimePass(millis)
}
