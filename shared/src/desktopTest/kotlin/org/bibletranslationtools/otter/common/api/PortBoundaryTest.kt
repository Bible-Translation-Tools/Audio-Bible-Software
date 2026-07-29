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

    private val apiRoot: File = run {
        // Gradle runs test tasks with the working directory set to the project dir (shared/),
        // but walk up as a fallback so the test also works from a repo-root invocation.
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "src/commonMain/kotlin").isDirectory) {
            dir = dir.parentFile
        }
        File(dir, "src/commonMain/kotlin/org/bibletranslationtools/otter/common/api")
    }

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
     * `api/persistence/` is interfaces (plus the `ModelTake` typealias) only. Concrete
     * repositories belong in `persistence/repositories/` — `WorkbookRepository` and
     * `WorkbookDatabaseAccessor` used to sit here, which made `api/` read as a mixed bag and
     * hid the layer boundary from anyone reading an import.
     *
     * Scoped to `api/persistence/` on purpose: `api/io/zip/` still holds the Nio/Android
     * `IFileReader`/`IFileWriter` implementations alongside their interfaces, which is a
     * separate cleanup.
     */
    @Test
    fun `persistence ports declare no concrete classes`() {
        val declaration = Regex("""^(?:abstract |sealed |data |open |internal |private )*(class|object)\s+(\w+)""")
        val violations = apiSources()
            .filter { it.absolutePath.contains("/api/persistence/") }
            .flatMap { file ->
                file.readLines()
                    .mapNotNull { declaration.find(it)?.groupValues?.get(2) }
                    .map { "${file.name} declares class/object $it" }
            }
        if (violations.isNotEmpty()) {
            fail(
                "api/persistence/ must contain only interfaces — move implementations to " +
                    "persistence/\n" + violations.joinToString("\n") { "  - $it" }
            )
        }
    }
}
