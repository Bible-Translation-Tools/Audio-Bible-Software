package org.bibletranslationtools.otter.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the dependency direction of the inner layers, as a ratchet rather than a pass/fail line.
 *
 * `data/` holds the entities — the innermost layer, the one nothing should be able to constrain.
 * It is not clean: entities expose RxRelay in their public API, take `java.io.File`, and reach out
 * into `domain/` for filesystem accessors. Fixing that is a real piece of work (it means changing
 * how takes are persisted), so these tests do not demand it. They pin the exact current set of
 * violations instead.
 *
 * That makes each rule a one-way ratchet, and the second half is the important half:
 *
 *  - a NEW violation fails, so the layer cannot get worse by accident;
 *  - a FIXED violation also fails, telling you to shrink the allowlist, so the record cannot
 *    silently overstate how bad things are.
 *
 * The allowlists are meant to reach zero. When one does, delete it and drop the `expected`
 * argument — the rule then simply holds.
 *
 * @see org.bibletranslationtools.otter.common.api.PortBoundaryTest for the `api/` package's rules,
 *   which are absolute — there is nothing to ratchet there.
 */
class LayerBoundaryTest {

    private val commonMainRoot: File = run {
        // Gradle runs test tasks with the working directory set to the project dir (shared/), but
        // walk up as a fallback so the test also works from a repo-root invocation.
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "src/commonMain/kotlin").isDirectory) {
            dir = dir.parentFile
        }
        File(dir, "src/commonMain/kotlin/org/bibletranslationtools/otter/common")
    }

    private val dataRoot: File = File(commonMainRoot, "data")

    private fun dataSources(): List<File> {
        assertTrue(
            dataRoot.isDirectory,
            "could not locate the data/ source root (looked at ${dataRoot.absolutePath})"
        )
        return dataRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Import lines only — a package name inside a comment or KDoc is not a dependency. */
    private fun importsOf(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").substringBefore(" as ").trim() }

    private fun File.pathInData(): String = toRelativeString(dataRoot).replace(File.separatorChar, '/')

    /**
     * Fails when [actual] differs from [expected] in either direction, and says which direction —
     * an added violation and a fixed one need opposite responses from whoever is reading.
     */
    private fun assertRatchet(
        rule: String,
        why: String,
        expected: Set<String>,
        actual: Set<String>
    ) {
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
     * The entity ↔ use-case cycle. `domain/` imports `data/` in 68 files, which is correct; these
     * four import back the other way, which makes the two packages mutually dependent.
     *
     * Three are the [org.bibletranslationtools.otter.common.domain.content.Recordable] interfaces,
     * which are arguably domain types an entity may implement. `Workbook` is the real problem: it
     * takes an `IDirectoryProvider` and constructs `SourceAudioAccessor`, `ArtworkAccessor` and
     * `ProjectFilesAccessor`, so the innermost object in the codebase is a factory for filesystem
     * access and every consumer that wants a file takes a whole `Workbook` to get one.
     */
    @Test
    fun `data does not depend on domain`() {
        val violations = dataSources().filter { file ->
            importsOf(file).any { it.startsWith("org.bibletranslationtools.otter.common.domain") }
        }.map { it.pathInData() }.toSet()

        assertRatchet(
            rule = "data/ must not import domain/ — that is a cycle between the two innermost layers.",
            why = "Entities are the layer nothing depends outward from. Workbook holding " +
                "domain accessors is what forces every file-touching use case to accept a Workbook.",
            expected = setOf(
                "workbook/Chapter.kt",   // Recordable
                "workbook/Chunk.kt",     // ResourceRecordable
                "workbook/Resource.kt",  // ResourceRecordable
                "workbook/Workbook.kt"   // ArtworkAccessor, SourceAudioAccessor, ProjectFilesAccessor
            ),
            actual = violations
        )
    }

    /**
     * The entity layer's public API is a mutable reactive graph: `AssociatedAudio.takes` is a
     * `ReplayRelay<Take>` and its own KDoc says "the persistence layer should respond by storing
     * them". Pushing onto a relay in a ViewModel is a database write, which is why `:shared` cannot
     * demote rxkotlin/rxrelay from `api` — see the note on those lines in shared/build.gradle.kts.
     */
    @Test
    fun `data does not depend on RxJava`() {
        val violations = dataSources().filter { file ->
            importsOf(file).any { it.startsWith("io.reactivex") || it.startsWith("com.jakewharton.rxrelay2") }
        }.map { it.pathInData() }.toSet()

        assertRatchet(
            rule = "data/ must not import RxJava or RxRelay — entities should not name an async library.",
            why = "While they do, both apps see RxJava through :shared's public API whether they " +
                "want it or not, and a relay push is an invisible database write.",
            expected = setOf(
                "workbook/AssociatedAudio.kt",
                "workbook/AssociatedTranslation.kt",
                "workbook/Book.kt",
                "workbook/BookElementContainer.kt",
                "workbook/Chapter.kt",
                "workbook/Chunk.kt",
                "workbook/ResourceGroup.kt",
                "workbook/Take.kt",
                "workbook/WorkbookDescriptor.kt"
            ),
            actual = violations
        )
    }

    /**
     * `Take.file` is a `java.io.File`, so an entity names the filesystem and `Take.checksum()` reads
     * from it. Any storage change that is not a local file — and any test that wants a take without
     * touching a disk — goes through this.
     */
    @Test
    fun `data does not depend on the filesystem`() {
        val violations = dataSources().filter { file ->
            importsOf(file).any { it.startsWith("java.io") || it.startsWith("java.nio") }
        }.map { it.pathInData() }.toSet()

        assertRatchet(
            rule = "data/ must not import java.io or java.nio — entities should not name the filesystem.",
            why = "An entity that holds a File cannot be built without one, and cannot be stored " +
                "anywhere else.",
            expected = setOf(
                "primitives/ResourceMetadata.kt",
                "primitives/Take.kt",
                "workbook/Take.kt"
            ),
            actual = violations
        )
    }
}
