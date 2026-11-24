package org.bibletranslationtools.orature.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.semantics.SemanticsActions
import org.bibletranslationtools.orature.e2e.fixtures.SourceAudioFixtures
import org.bibletranslationtools.orature.e2e.harness.OratureUiTestHarness
import org.bibletranslationtools.orature.verify.StateDump
import org.bibletranslationtools.orature.verify.savedChunks
import java.io.File
import org.bibletranslationtools.orature.ui.OratureApp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The start of an oral translation: create a translation project from English, listen to the
 * source audio for Genesis 1 (Consume), split it into chunks (Chunking), and move on to drafting.
 *
 * Translation needs source audio, so setup imports the checked-in Genesis 1 source audio
 * fixture first, as a user would with Import or Choose File. Screenshots and a README go to
 * `app-orature/build/walkthroughs/chunking-journey/`.
 */
@OptIn(ExperimentalTestApi::class)
class OratureChunkingWalkthroughTest {

    private lateinit var dataDir: File

    @BeforeTest
    fun setUp() {
        dataDir = OratureUiTestHarness.start()
        SourceAudioFixtures.import(SourceAudioFixtures.englishGenesis1)
    }

    @AfterTest
    fun tearDown() {
        OratureUiTestHarness.stop()
    }

    @Test
    fun chunkGenesisOne() = runOratureUiTest {
        setContent { OratureApp(startWithSplash = false) }
        val guide = Walkthrough(
            test = this,
            name = "chunking-journey",
            title = "Starting an oral translation in Orature",
            intro = "Oral translation means listening to Scripture in a language you know and " +
                "speaking it in your own. Orature takes you through it in steps. This walks " +
                "through the first two: listening to the source (Consume) and splitting it into " +
                "chunks small enough to remember and retell (Chunking)."
        )
        guide.run { journey(this@runOratureUiTest, guide) }
    }

    private suspend fun journey(test: ComposeUiTest, guide: Walkthrough) = with(test) {

        // ── Create the project ──────────────────────────────────────────────────────────
        waitFor(hasContentDescription("New Project"), timeoutMillis = 180_000)

        guide.step("Press + (New Project) and pick Oral Translation") {
            tap(hasContentDescription("New Project"))
            waitFor(hasText("Pick a Project Type (1 of 4)"))
        }

        tap(hasText("Oral Translation") and hasClickAction())
        waitFor(hasText("Pick a Source Language (2 of 4)"))
        guide.step("Pick the source: the language you will listen to. Here, English") {
            onNode(hasSetTextAction()).performTextInput("English")
            waitFor(listRow("English"))
        }

        tap(listRow("English"))
        waitFor(hasText("Pick a Target Language (3 of 4)"), timeoutMillis = 60_000)
        guide.step("Pick the target: the language you will speak the translation in") {
            onNode(hasSetTextAction()).performTextInput("Spanish")
            waitFor(listRow("es"))
        }

        tap(listRow("es")) // Español (Spanish); the search also lists other Spanish varieties
        finishWizard(guide)
        guide.snapshot("The project is created, with its books listed in the middle")

        // ── Consume ─────────────────────────────────────────────────────────────────────
        val playSource = hasText("Play Source") and hasClickAction()
        guide.step("Open Genesis. Chapter 1 opens on the first step, Consume: listen to the whole chapter") {
            tap(listRow("Genesis"))
            waitFor(playSource, timeoutMillis = 120_000)
        }

        guide.step("Press Play Source. The marks on the waveform are where each verse starts") {
            tap(playSource)
            waitFor(hasText("Pause") and hasClickAction())
            letTimePass(2_000)
        }
        tap(hasText("Pause") and hasClickAction())

        // ── Chunking ────────────────────────────────────────────────────────────────────
        val addChunk = hasText("Add Chunk") and hasClickAction()
        guide.step("Go to Chunking (steps, on the left). Here you split the chapter into chunks") {
            tap(hasText("Chunking") and hasClickAction())
            waitFor(addChunk, timeoutMillis = 60_000)
        }

        repeat(3) { n ->
            togglePlay()
            letTimePass(3_000)
            togglePlay()
            tap(addChunk and isEnabled())
            if (n == 0) guide.snapshot("Play, stop where a thought ends, and press Add Chunk. A chunk marker appears")
        }
        guide.snapshot("Three chunks marked. Each is a part short enough to remember and retell")

        // ── On to drafting: blocked on main ─────────────────────────────────────────────
        // On a chapter with no chunks yet, the journey cannot continue to Blind Draft:
        // - Blind Draft unlocks only once chunks are saved (computeReachableStep: no chunks
        //   means CHUNKING is the furthest step);
        // - chunks are saved only when leaving Chunking FORWARD (commitStepNavigation, and
        //   OratureChunkingViewModel.onCleared);
        // - and forward is Blind Draft, which is locked. Going back to Consume saves nothing.
        // Once that is fixed, continue here: tap Blind Draft and wait for the chunk list.
        guide.snapshot("Next is Blind Draft, but it stays locked: the new chunks are not saved until you move forward past Chunking (a bug)")
        check(!isShowing(hasText("Blind Draft and Self Edit") and hasClickAction())) {
            "Blind Draft is reachable now; extend this journey into drafting"
        }
        // The same bug in the saved files: three chunks are on screen, none are saved.
        val dump = StateDump.dump(dataDir, "gen")
        File("build/walkthroughs/chunking-journey/state.json").writeText(StateDump.toText(dump))
        check(dump.savedChunks() == null) {
            "Chunks are saved now (${dump.savedChunks()}); the chunking bug may be fixed: extend this journey into drafting"
        }
    }

    /**
     * The Chunking step's play/pause button has an icon and no label, so it is found as the
     * clickable between Previous Chunk and Next Chunk.
     */
    private fun ComposeUiTest.togglePlay() {
        onNode(hasContentDescription("Next Chunk"))
            .onParent()
            .onChildren()
            .filter(hasClickAction() and !hasContentDescription("Next Chunk") and !hasContentDescription("Previous Chunk") and !hasText("Add Chunk"))
            .onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
    }
}

private operator fun SemanticsMatcher.not(): SemanticsMatcher =
    SemanticsMatcher("NOT ($description)") { !this.matches(it) }
