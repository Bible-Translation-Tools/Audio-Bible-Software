package org.bibletranslationtools.bttrecorder2.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTextExactly
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Full wizard create — parity with Android [CreateProjectWizardFlowTest]:
 * Files → New Project → English → Afar → Genesis → Project Management → open it → a chapter's verses.
 *
 * The tail end is not decoration. This test used to stop at "Genesis is listed in Project Management",
 * and for that reason it passed for weeks over a project every chapter of which was empty:
 * `ProjectCreationViewModel` was creating targets with `deriveProjectFromVerses = false`, which derives
 * chapters and no verse rows. `ProjectCreateTest` pins both of those shapes at the domain level, but it
 * supplies the argument itself, so only a test that drives the real wizard can catch the app asking for
 * the wrong one. Walking into a chapter is what makes this that test.
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
        // The project LIST's own row, not any node containing "Genesis". The wizard's book-search row
        // reads "Genesis, gen" and is still composed at this point, so a substring wait here is
        // satisfied by the screen we just came from and proves nothing about the project having been
        // created — which is what it was doing before. This group header only exists on a populated
        // project list.
        waitUntil(timeoutMillis = 60_000) {
            onAllNodesWithText("from English", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // ── and then open it, because "the project appears" was not enough ──────────────────
        //
        // A project can be created, listed, and opened while being completely unrecordable: the
        // target book derives its chapters either way, and its VERSE rows only when
        // `ProjectCreationViewModel` asks for them. With that argument false, everything above
        // still passed and every chapter in the new project was empty.
        //
        // hasTextExactly, because neither a substring nor an exact-text match can tell these two
        // apart: the wizard's book row is a merged node carrying the text LIST ["Genesis", "gen"], and
        // `onNodeWithText("Genesis")` asks whether that list contains "Genesis" — which it does. The
        // project card's list is exactly ["Genesis"], and only matching the whole list picks it.
        onNode(hasTextExactly("Genesis")).performClick()

        // Chapter rows are "Chapter <title>", where the title comes from the collection, so match on
        // the label and let the number be whatever the import produced.
        waitUntil(timeoutMillis = 120_000) {
            onAllNodesWithText("Chapter", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("No active project").fetchSemanticsNodes().isNotEmpty()
        }
        // Opening a project persists it as the active workbook, and the chapter list opens by
        // reading that back — so navigating before the write lands reports this instead of a list.
        assertTrue(
            onAllNodesWithText("No active project").fetchSemanticsNodes().isEmpty(),
            "the chapter list must not open on a workbook the prefs write has not persisted yet"
        )

        onAllNodesWithText("Chapter", substring = true)[0].performClick()

        // Waits for the verse rows themselves, NOT merely for the verse screen to be up. The screen's
        // title renders before the chunks flow has emitted, so accepting it here made the assertion
        // below race the list and fail whether or not the verses were coming — which is worth stating
        // because it was tried: it turns this into a test that fails identically in both directions.
        //
        // The timeout IS the regression signal, so it is caught and given the reason: an empty chapter
        // shows no verses and no error, so there is nothing else for the wait to observe.
        try {
            waitUntil(timeoutMillis = 60_000) {
                onAllNodesWithText("Verse", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodesWithText("No active chapter").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            fail(
                "a newly created project's chapter has to contain verse rows — a target derived " +
                    "without them lists its chapters and offers nothing to record into",
                timeout
            )
        }
        assertTrue(
            onAllNodesWithText("No active chapter").fetchSemanticsNodes().isEmpty(),
            "the verse list must not open on a chapter the prefs write has not persisted yet"
        )
    }
}
