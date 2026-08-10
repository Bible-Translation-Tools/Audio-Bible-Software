package org.bibletranslationtools.otter.common.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the dependency direction of the ports package.
 *
 * `api/` holds the interfaces the inner layers own and the adapters implement. It must not
 * know anything about how those interfaces are satisfied: no jOOQ, no generated `otter-db`
 * schema, and no imports reaching back into the `persistence/` adapter layer. Violating that
 * makes the whole codebase compile-time coupled to the database library through the very
 * package whose job is to prevent it.
 *
 * This is enforced as a source-scanning test rather than a convention because the previous
 * violation ([IAppDatabase][org.bibletranslationtools.otter.common.persistence.database.IAppDatabase],
 * which exposed a `DSLContext`, 15 jOOQ DAOs, and executable jOOQ in interface default
 * bodies) survived for the whole port from the JavaFX app without anyone noticing.
 *
 * Depending on `data/` and on `domain/` is allowed and expected — ports are declared in terms
 * of domain types (e.g. `IVersificationRepository` returns a `Versification`).
 */
class PortBoundaryTest {

    private val commonMainRoot: File = run {
        // Gradle runs test tasks with the working directory set to the project dir (shared/),
        // but walk up as a fallback so the test also works from a repo-root invocation.
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "src/commonMain/kotlin").isDirectory) {
            dir = dir.parentFile
        }
        File(dir, "src/commonMain/kotlin/org/bibletranslationtools/otter/common")
    }

    private val apiRoot: File = File(commonMainRoot, "api")

    private fun apiSources(): List<File> {
        assertTrue(
            apiRoot.isDirectory,
            "could not locate the api/ source root (looked at ${apiRoot.absolutePath})"
        )
        val sources = apiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "found no Kotlin sources under ${apiRoot.absolutePath}")
        return sources
    }

    /** Import lines only — a package name inside a comment or KDoc is not a dependency. */
    private fun importsOf(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").trim() }

    private fun assertNoImportMatching(
        forbidden: String,
        rationale: String,
        predicate: (String) -> Boolean
    ) {
        val violations = apiSources().flatMap { file ->
            importsOf(file).filter(predicate).map { "${file.name} imports $it" }
        }
        if (violations.isNotEmpty()) {
            fail(
                "api/ must not depend on $forbidden — $rationale\n" +
                    violations.joinToString("\n") { "  - $it" }
            )
        }
    }

    @Test
    fun `ports do not depend on jOOQ`() = assertNoImportMatching(
        forbidden = "jOOQ",
        rationale = "the database library belongs to the persistence adapter layer."
    ) { it.startsWith("org.jooq") }

    @Test
    fun `ports do not depend on the generated database schema`() = assertNoImportMatching(
        forbidden = "the generated otter-db schema",
        rationale = "generated table/record types are an adapter detail."
    ) { it.startsWith("org.bibletranslationtools.otter_db") }

    @Test
    fun `ports do not depend on the persistence adapter layer`() = assertNoImportMatching(
        forbidden = "otter.common.persistence",
        rationale = "that is a reverse edge: adapters depend on ports, never the other way."
    ) { it.startsWith("org.bibletranslationtools.otter.common.persistence") }

    /**
     * The inner layers must not depend on a UI framework.
     *
     * `initialization/` and `domain/project/` used to read the bundled GL zips, langnames
     * catalog, source manifests, and versification json straight off the Compose
     * Multiplatform `Res` object — a UI-framework dependency in the domain layer, plus a
     * `runBlocking` at each call site to bridge its suspend API. They now go through
     * [org.bibletranslationtools.otter.common.api.io.IBundledContentSource], whose only
     * implementation knows about Compose.
     */
    @Test
    fun `backend layers do not depend on Compose`() {
        val layers = listOf("api", "data", "domain", "initialization", "persistence")
        val violations = layers.flatMap { layer ->
            val root = File(commonMainRoot, layer)
            if (!root.isDirectory) return@flatMap emptyList()
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    importsOf(file)
                        .filter { it.startsWith("org.jetbrains.compose") || it.startsWith("androidx.compose") }
                        .map { "$layer/${file.name} imports $it" }
                }
                .toList()
        }
        if (violations.isNotEmpty()) {
            fail(
                "the backend layers must not depend on Compose — read bundled content through " +
                    "IBundledContentSource instead of the generated Res object\n" +
                    violations.joinToString("\n") { "  - $it" }
            )
        }
    }

    /**
     * `api/` is interfaces (plus the `ModelTake` typealias) only — implementations live in the
     * layer that owns them.
     *
     * It used to be a mixed bag: `WorkbookRepository` and `WorkbookDatabaseAccessor` sat in
     * `api/persistence/repositories/`, and `api/io/zip/` held the Nio/`java.util.zip`
     * `IFileReader`/`IFileWriter` implementations next to their interfaces. Anyone reading
     * `api/` as a port package was wrong, and an import no longer told you which side of the
     * boundary you were on. Those now live in `persistence/repositories/` and `io/zip/`.
     */
    @Test
    fun `ports declare no concrete classes`() {
        val declaration = Regex("""^(?:abstract |sealed |data |open |internal |private )*(class|object)\s+(\w+)""")
        val violations = apiSources().flatMap { file ->
            file.readLines()
                .mapNotNull { declaration.find(it)?.groupValues?.get(2) }
                .map { "${file.toRelativeString(apiRoot)} declares class/object $it" }
        }
        if (violations.isNotEmpty()) {
            fail(
                "api/ must contain only interfaces — move implementations into the layer that " +
                    "owns them\n" + violations.joinToString("\n") { "  - $it" }
            )
        }
    }
}
