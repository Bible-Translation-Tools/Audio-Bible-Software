package org.bibletranslationtools.orature.verify

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import java.io.File
import kotlin.test.Test
import org.bibletranslationtools.orature.e2e.createGenesisTranslation
import org.bibletranslationtools.orature.e2e.fixtures.SourceAudioFixtures
import org.bibletranslationtools.orature.e2e.harness.OratureUiTestHarness
import org.bibletranslationtools.orature.e2e.narrateGenesisOne
import org.bibletranslationtools.orature.e2e.runOratureUiTest
import org.bibletranslationtools.orature.ui.OratureApp

/**
 * Ready-made app data to start a check from, so an agent (or a person) verifying a feature
 * begins at the feature instead of in the project wizard. Each state is a complete Orature data
 * directory, built by driving the real app, under `app-orature/build/verify-states/<name>/`.
 *
 * Build them with `./gradlew :app-orature:seedVerifyStates` (all) or
 * `-PverifyState=<name>` (one). Open one with `./gradlew :app-orature:runVerifyApp -PverifyState=<name>`.
 */
@OptIn(ExperimentalTestApi::class)
internal enum class VerifyState(val description: String, val setup: ComposeUiTest.() -> Unit) {
    FRESH(
        "Orature after first launch, with Genesis 1 source audio imported. Home, no projects.",
        {}
    ),
    NARRATED_GEN1(
        "A Narration project (English Genesis) with chapter 1 narrated: every line recorded at " +
            "330 Hz, about 1 s each. Open Genesis to re-record verses or move markers.",
        { narrateGenesisOne() }
    ),
    TRANSLATION_GEN1(
        "An Oral Translation project (English to Spanish) with Genesis 1 open on Consume. " +
            "Source audio has 31 verses, one tone each (0.6 s on, 0.15 s off).",
        { createGenesisTranslation() }
    );

    val slug: String get() = name.lowercase().replace('_', '-')

    companion object {
        val root: File = File("build/verify-states").absoluteFile
        fun of(slug: String): VerifyState =
            entries.firstOrNull { it.slug == slug }
                ?: error("Unknown state '$slug'. Known: ${entries.joinToString { it.slug }}")
    }
}

/**
 * Builds the verification states. Does nothing unless SEED_VERIFY_STATES is set (to a state's
 * name, or `all`), so normal test runs skip it; `./gradlew :app-orature:seedVerifyStates` sets it.
 */
class SeedVerifyStatesTest {

    @Test
    fun seed() {
        val wanted = System.getenv("SEED_VERIFY_STATES") ?: return
        val states = if (wanted == "all") VerifyState.entries else wanted.split(',').map { VerifyState.of(it.trim()) }
        states.forEach(::seed)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun seed(state: VerifyState) {
        val dir = VerifyState.root.resolve(state.slug)
        dir.deleteRecursively()
        OratureUiTestHarness.start(dataDir = dir)
        try {
            SourceAudioFixtures.import(SourceAudioFixtures.englishGenesis1)
            runOratureUiTest {
                setContent { OratureApp(startWithSplash = false) }
                state.setup(this)
            }
        } finally {
            OratureUiTestHarness.stop()
        }
        dir.resolve("VERIFY_STATE.txt").writeText("${state.slug}\n\n${state.description}\n")
        println("Seeded ${state.slug} in $dir")
    }
}
