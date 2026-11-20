package org.bibletranslationtools.orature.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import org.bibletranslationtools.orature.e2e.harness.OratureUiTestHarness
import org.bibletranslationtools.orature.ui.OratureApp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Fixing a narration after it is recorded: re-record one verse, move a verse marker to where the
 * verse really starts, and undo. Setup narrates Genesis 1 without screenshots (the narration
 * journey shows that part). Screenshots and a README go to
 * `app-orature/build/walkthroughs/narration-editing/`.
 */
@OptIn(ExperimentalTestApi::class)
class OratureNarrationEditingWalkthroughTest {

    @BeforeTest
    fun setUp() {
        OratureUiTestHarness.start()
    }

    @AfterTest
    fun tearDown() {
        OratureUiTestHarness.stop()
    }

    @Test
    fun reRecordAVerseAndMoveAMarker() = runOratureUiTest {
        setContent { OratureApp(startWithSplash = false) }
        val guide = Walkthrough(
            test = this,
            name = "narration-editing",
            title = "Fixing a narration in Orature",
            intro = "After a chapter is narrated, any verse can be recorded again, and the markers " +
                "that split the recording into verses can be moved. This walks through both, " +
                "and undoing a change."
        )
        guide.run { journey(this@runOratureUiTest, guide) }
    }

    private suspend fun journey(test: ComposeUiTest, guide: Walkthrough) = with(test) {
        narrateGenesisOne()
        guide.snapshot("Genesis 1 is narrated. Every line has a recording, and each has a Re-Record button")

        // ── Re-record verse 2 ───────────────────────────────────────────────────────────
        val verse2 = "The earth was without form"
        // The lines are a lazy list (rows exist only on screen): book title, chapter, verse 1, verse 2.
        onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        letTimePass(500)
        guide.step("Press Re-Record beside verse 2 and read it again. Only this verse is replaced") {
            tap(buttonBeside(verse2, "Re-Record"))
            waitFor(saveButton)
            letTimePass(1_500)
        }

        guide.step("Press Save. The new take replaces the old one; the other verses are untouched") {
            tap(saveButton)
            waitUntil(timeoutMillis = 60_000) { !isShowing(saveButton) }
            waitFor(hasText("Re-Record") and hasClickAction() and isEnabled(), timeoutMillis = 60_000)
        }

        // ── Move a marker ───────────────────────────────────────────────────────────────
        val undo = hasContentDescription("Undo") and hasClickAction()
        val label = waitForMarker()
        val before = markerX(label)
        guide.snapshot("The waveform at the top has a marker where each verse starts (here, verse $label)")

        guide.step("Drag the verse $label marker to where the verse really starts. The recording itself is not changed") {
            // The first ~18 px only start the drag (touch slop); a marker cannot pass the next verse's.
            dragMarker(label, byPx = 45f)
            waitUntil(timeoutMillis = 10_000) { markerX(label) > before + 10 }
        }

        guide.step("Press Undo (top bar) to put the marker back. Redo does it again") {
            tap(undo and isEnabled())
            waitUntil(timeoutMillis = 10_000) { kotlin.math.abs(markerX(label) - before) < 4 }
        }
    }

    // ── Setup: narrate Genesis 1 without screenshots ────────────────────────────────────

    private fun ComposeUiTest.narrateGenesisOne() {
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

    private val saveButton = hasText("Save") and hasClickAction()

    // ── Finding things by where they are on screen ──────────────────────────────────────

    /**
     * The [label] button on the same teleprompter row as the verse whose text contains
     * [verseText]: every row has its own Re-Record button, so the row's vertical position is what
     * tells them apart.
     */
    private fun ComposeUiTest.buttonBeside(verseText: String, label: String): SemanticsMatcher {
        waitFor(hasText(verseText, substring = true))
        val row = onNode(hasText(verseText, substring = true)).fetchSemanticsNode().boundsInRoot
        return hasText(label) and hasClickAction() and SemanticsMatcher("on the row of '$verseText'") {
            val b = it.boundsInRoot
            b.center.y in row.top..row.bottom
        }
    }

    /**
     * A verse marker on the waveform to drag: verse 3 when it is in view, else whichever is
     * nearest the middle. Markers have no semantics of their own, so the number on each marker's
     * chip is the handle. The waveform is the top of the screen; verse numbers in the text below
     * it are not markers.
     */
    private fun ComposeUiTest.waitForMarker(): String {
        var found: String? = null
        waitUntil(timeoutMillis = 30_000) {
            val labels = markerChips().map { it.first }
            found = if ("3" in labels) "3" else {
                val middle = onRoot().fetchSemanticsNode().boundsInRoot.center.x
                markerChips().minByOrNull { kotlin.math.abs(it.second.center.x - middle) }?.first
            }
            found != null
        }
        return found!!
    }

    /** The number on each marker chip in the waveform, with where that number is drawn. */
    private fun ComposeUiTest.markerChips(): List<Pair<String, Rect>> =
        onAllNodes(hasTextMatching(Regex("\\d+")))
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.bottom < WAVEFORM_BOTTOM_PX && it.boundsInRoot.left > 0 }
            .map { it.config[SemanticsProperties.Text].joinToString("") { t -> t.text } to it.boundsInRoot }

    /**
     * Where marker [label]'s line is. The chip sits at the bottom of the line: border, padding and
     * a bookmark icon come before the number, and the line is half a grab-width from the chip's
     * left edge (OratureAudioWorkspace's MarkerNode), so the line is [LINE_LEFT_OF_LABEL_PX] left
     * of the number. Takes the last chip with that number, as "1" is both the chapter and verse 1.
     * Markers outside the visible part of the waveform are drawn far off to the left, so they are skipped.
     */
    private fun ComposeUiTest.markerX(label: String): Float =
        markerChips().last { it.first == label }.second.left - LINE_LEFT_OF_LABEL_PX

    private fun hasTextMatching(regex: Regex) = SemanticsMatcher("text matches $regex") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { regex.matches(it.text) } == true
    }

    /** Drags marker [label] sideways by [byPx], pressing on its line above the chip. */
    private fun ComposeUiTest.dragMarker(label: String, byPx: Float) {
        val x = markerX(label)
        val y = markerChips().last { it.first == label }.second.top - 100f
        onRoot().performTouchInput {
            down(Offset(x, y))
            repeat(16) { moveBy(Offset(byPx / 16, 0f)) }
            up()
        }
        letTimePass(500)
    }

    private companion object {
        /** The waveform fills roughly the top 360 px of the 1024x768 test window. */
        const val WAVEFORM_BOTTOM_PX = 360f
        /** Chip border 1 + padding 4 + icon 18 + gap 2, less half the 24 dp grab strip (density 1). */
        const val LINE_LEFT_OF_LABEL_PX = 13f
    }
}
