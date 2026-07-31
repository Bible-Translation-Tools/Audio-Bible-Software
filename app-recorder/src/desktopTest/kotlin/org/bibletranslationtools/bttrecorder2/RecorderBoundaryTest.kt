package org.bibletranslationtools.bttrecorder2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the recorder app's own boundaries, as ratchets. Orature has the same rules in
 * `OratureBoundaryTest`; the ~20 lines of source-scanning are duplicated per module because
 * Gradle does not share test code across KMP modules without extra build configuration, and unlike
 * production logic a scanner has no behaviour to drift.
 *
 * Two of these rules currently hold with an empty allowlist. They are here to keep it that way —
 * both were true by accident until recently, and nothing stopped the next commit from undoing them.
 */
class RecorderBoundaryTest {

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
     * `shared.logging` exists because Orature had 36 of these and this app had none — a difference
     * that was invisible until someone counted. Desktop logging is configured to write to a file
     * that the app can show the user; anything printed to stderr is absent from it.
     */
    @Test
    fun `nothing prints to stderr or stdout`() {
        val violations = sources().filter { file ->
            // This file names both calls as string literals to look for them.
            file.name != "RecorderBoundaryTest.kt" &&
                file.readLines().any { line ->
                    val code = line.substringBefore("//")
                    "System.err.println" in code || "System.out.println" in code
                }
        }.map { it.modulePath() }.toSet()

        assertRatchet(
            rule = "Use shared.logging (logFailure / logDebug) instead of println.",
            why = "Desktop logging goes to a file the app surfaces; stderr does not appear in it.",
            expected = emptySet(),
            actual = violations
        )
    }

    /**
     * A ViewModel implementing `KoinComponent` is a service locator: it reaches the whole graph
     * through `by inject()`, so the compiler cannot see its dependencies, no boundary test can
     * either, and a missing binding surfaces at first use rather than at startup.
     *
     * Constructor injection is the direction of travel (`PlaybackViewModel`, `RecorderViewModel`,
     * `ProjectManagementViewModel`'s collaborators). This list should only shrink.
     */
    @Test
    fun `no new ViewModel uses Koin as a service locator`() {
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
                "ChapterListViewModel",
                "ExportProjectViewModel",
                "MainMenuViewModel",
                "ProjectCreationViewModel",
                "ProjectManagementViewModel",
                "SettingsViewModel",
                "SplashScreenViewModel",
                "UnitListViewModel"
            ),
            actual = violations
        )
    }

    /**
     * Composables should be handed what to render, not a filesystem. A screen that builds a `File`
     * is doing work that belongs in a ViewModel or a use case, and it cannot be tested without one.
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
            expected = emptySet(),
            actual = violations
        )
    }
}
