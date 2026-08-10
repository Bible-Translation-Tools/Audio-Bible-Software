package org.bibletranslationtools.orature

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards Orature's own boundaries, as ratchets. The recorder has the same rules in
 * `RecorderBoundaryTest`; the ~20 lines of source-scanning are duplicated per module because Gradle
 * does not share test code across KMP modules without extra build configuration, and unlike
 * production logic a scanner has no behaviour to drift.
 *
 * A new violation fails the build; so does a FIXED one, which tells you to shrink the allowlist.
 * That second half is what stops these lists quietly becoming a permanent record of defeat.
 */
class OratureBoundaryTest {

    private val moduleRoot: File = run {
        // Gradle sets the working directory to the project dir; walk up as a fallback.
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "src/commonMain/kotlin").isDirectory) {
            dir = dir.parentFile
        }
        dir
    }

    private val sourceRoot: File = File(moduleRoot, "src")

    private fun sources(): List<File> {
        assertTrue(
            sourceRoot.isDirectory,
            "could not locate the src/ root (looked at ${sourceRoot.absolutePath})"
        )
        val all = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(all.isNotEmpty(), "found no Kotlin sources under ${sourceRoot.absolutePath}")
        return all
    }

    private fun File.modulePath(): String =
        toRelativeString(sourceRoot).replace(File.separatorChar, '/')
            .substringAfter("/kotlin/org/bibletranslationtools/")

    private fun importsOf(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").substringBefore(" as ").trim() }

    private fun assertRatchet(rule: String, why: String, expected: Set<String>, actual: Set<String>) {
        val added = (actual - expected).sorted()
        val fixed = (expected - actual).sorted()
        if (added.isNotEmpty()) {
            fail(
                "$rule\n$why\nNEW violations — do not add to the allowlist to make this pass:\n" +
                    added.joinToString("\n") { "  - $it" }
            )
        }
        if (fixed.isNotEmpty()) {
            fail(
                "$rule\nThese no longer violate it. Remove them from this test's allowlist so it " +
                    "keeps ratcheting:\n" + fixed.joinToString("\n") { "  - $it" }
            )
        }
    }

    /**
     * Failures must reach the log, not stderr.
     *
     * This module had 36 `System.err.println` calls while the recorder had none. 24 of them were
     * failures being absorbed into UI state, so an "export failed" report arrived with an empty
     * `orature.log` — the file the Info drawer's "View Logs" opens. They go through
     * `shared.logging` now, and this keeps the count at zero.
     */
    @Test
    fun `nothing prints to stderr or stdout`() {
        val violations = sources().filter { file ->
            // This file names both calls as string literals to look for them.
            file.name != "OratureBoundaryTest.kt" &&
                file.readLines().any { line ->
                    val code = line.substringBefore("//")
                    "System.err.println" in code || "System.out.println" in code
                }
        }.map { it.modulePath() }.toSet()

        assertRatchet(
            rule = "Use shared.logging (logFailure / logDebug) instead of println.",
            why = "Desktop logging writes to orature.log, which the Info drawer shows the user; " +
                "stderr does not appear in it.",
            expected = emptySet(),
            actual = violations
        )
    }

    /**
     * A class implementing `KoinComponent` is a service locator: it reaches the whole graph through
     * `by inject()`, so the compiler cannot see its dependencies and no boundary test can either.
     * A missing binding then surfaces at first use — which is how `OratureProjectDeletion` went
     * unbound and showed up as seven wizard tests timing out with nothing in the log.
     *
     * 18 of these against the recorder's 8. The list should only shrink; take collaborators as
     * constructor parameters instead.
     */
    @Test
    fun `no new class uses Koin as a service locator`() {
        val declaration =
            Regex("""^(?:internal |private |abstract |open |sealed )*(?:class|object)\s+(\w+)([^{]*)\{""", RegexOption.MULTILINE)
        val violations = sources().flatMap { file ->
            declaration.findAll(file.readText())
                .filter { "KoinComponent" in it.groupValues[2] }
                .map { it.groupValues[1] }
        }.toSet()

        assertRatchet(
            rule = "Take collaborators as constructor parameters instead of implementing KoinComponent.",
            why = "`by inject()` hides the dependency from the compiler and defers a missing " +
                "binding to first use.",
            expected = setOf(
                "OratureBlindDraftViewModel",
                "OratureChapterReviewViewModel",
                "OratureChunkingViewModel",
                "OratureConsumeViewModel",
                "OratureContributorViewModel",
                "OratureExportProjectViewModel",
                "OratureHomeViewModel",
                "OratureImportViewModel",
                "OratureInfoViewModel",
                "OratureNarrationViewModel",
                "OraturePeerEditViewModel",
                "OraturePluginStore",
                "OraturePluginViewModel",
                "OratureProjectDeletion",
                "OratureSettingsViewModel",
                "OratureSplashViewModel",
                "OratureTranslationViewModel",
                "OratureVerseMarkerViewModel"
            ),
            actual = violations
        )
    }

    /**
     * Composables should be handed what to render, not a filesystem. All three below build a `File`
     * to hand to a picker or a backup path — work that belongs in the ViewModel that already owns
     * the surrounding state.
     */
    @Test
    fun `screens and components do not touch the filesystem`() {
        val violations = sources().filter { file ->
            val path = file.modulePath()
            ("ui/screens/" in path || "ui/components/" in path) &&
                importsOf(file).any { it.startsWith("java.io") || it.startsWith("java.nio") }
        }.map { it.modulePath() }.toSet()

        assertRatchet(
            rule = "ui/screens and ui/components must not import java.io or java.nio.",
            why = "A composable that resolves paths cannot be rendered in a test without a disk.",
            expected = setOf(
                "orature/ui/components/OratureExportProjectDialog.kt",
                "orature/ui/components/OratureQuickBackup.kt",
                "orature/ui/screens/OratureBlindDraftScreen.kt"
            ),
            actual = violations
        )
    }
}
