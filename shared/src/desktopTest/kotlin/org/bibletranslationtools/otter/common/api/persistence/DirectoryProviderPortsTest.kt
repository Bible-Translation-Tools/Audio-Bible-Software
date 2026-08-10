package org.bibletranslationtools.otter.common.api.persistence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Holds the line on the directory-port split.
 *
 * [IDirectoryProvider] used to declare 27 members: app data locations, per-project locations,
 * resource container locations, temp file handling, and two `IFileReader`/`IFileWriter`
 * factories. Every consumer compiled against all of it — a use case that only needed
 * `createTempFile` was coupled to the database directory and the RC layout, and the interface
 * grew because adding one more member to the object everyone already had was always the path
 * of least resistance.
 *
 * It is now the composite of five narrow ports and declares nothing itself. These tests fail
 * if a member is added back to the composite, or if the split stops covering the whole surface.
 */
class DirectoryProviderPortsTest {

    private val apiPersistenceRoot: File = run {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "src/commonMain/kotlin").isDirectory) {
            dir = dir.parentFile
        }
        File(dir, "src/commonMain/kotlin/org/bibletranslationtools/otter/common/api/persistence")
    }

    private val narrowPorts = listOf(
        "IAppDirectories",
        "ITempFileProvider",
        "IProjectDirectories",
        "IResourceContainerDirectories",
        "IFileIOFactory",
    )

    private fun sourceOf(simpleName: String): List<String> {
        val file = File(apiPersistenceRoot, "$simpleName.kt")
        assertTrue(file.isFile, "expected a port declaration at ${file.absolutePath}")
        return file.readLines()
    }

    /** Declaration lines only — KDoc prose mentioning `createTempFile` is not a member. */
    private fun membersOf(simpleName: String): List<String> {
        val member = Regex("""^\s{4}(?:fun|val|var)\s+(\w+)""")
        return sourceOf(simpleName).mapNotNull { member.find(it)?.groupValues?.get(1) }
    }

    @Test
    fun `the composite declares no members of its own`() {
        val members = membersOf("IDirectoryProvider")
        if (members.isNotEmpty()) {
            fail(
                "IDirectoryProvider must stay a pure composite of ${narrowPorts.joinToString()} " +
                    "— declare new members on the narrow port that owns the concern, or add a " +
                    "new port. Found: ${members.joinToString()}"
            )
        }
    }

    @Test
    fun `the composite extends exactly the five narrow ports`() {
        val text = sourceOf("IDirectoryProvider").joinToString("\n")
        val supertypes = text.substringAfter("interface IDirectoryProvider :")
            .split(",")
            .map { it.trim().removeSuffix("{").trim() }
            .filter { it.isNotEmpty() }
        assertEquals(
            narrowPorts.sorted(),
            supertypes.sorted(),
            "the composite's supertypes drifted from the five narrow directory ports"
        )
    }

    /**
     * The point of the split is that each concern has exactly one home. If the same member name
     * appears on two ports, a consumer can satisfy it from either and the seam stops meaning
     * anything — `tempDirectory` in particular was the member most tempting to duplicate onto
     * the app-directories port.
     *
     * Compared across *distinct* ports: a name repeating within one port is an overload pair
     * (`getProjectDirectory` by `Collection` and by slug), which is the intended shape.
     */
    @Test
    fun `no member is declared on more than one narrow port`() {
        val owners = mutableMapOf<String, MutableSet<String>>()
        narrowPorts.forEach { port ->
            membersOf(port).forEach { owners.getOrPut(it) { mutableSetOf() }.add(port) }
        }
        val duplicated = owners.filterValues { it.size > 1 }
        if (duplicated.isNotEmpty()) {
            fail(
                "each directory concern must have one owning port\n" +
                    duplicated.entries.joinToString("\n") { (m, ps) -> "  - $m on ${ps.joinToString(" and ")}" }
            )
        }
    }

    /**
     * `openInFileManager(path: String)` was on the interface with zero callers: both platform
     * providers implemented it (Android's was `TODO("Not yet implemented")`, i.e. it would have
     * thrown), while every real caller went through :app-orature's own
     * `platform/FileManager.kt` `expect fun openInFileManager(file: File)`. Launching a file
     * manager is a UI concern; a directory provider has no business doing it.
     */
    @Test
    fun `opening a file manager is not a directory-provider concern`() {
        val offenders = (narrowPorts + "IDirectoryProvider").filter { port ->
            membersOf(port).any { it == "openInFileManager" }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "openInFileManager belongs to the UI layer (:app-orature platform/FileManager.kt), " +
                    "not to a directory port — found on ${offenders.joinToString()}"
            )
        }
    }
}
