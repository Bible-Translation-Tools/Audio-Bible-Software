package org.bibletranslationtools.otter.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The progress meter of the RxJava to coroutines migration: how many main-code files import
 * RxJava, RxRelay or the Rx bridge, per package, in `:shared` and both apps.
 *
 * A ratchet, in the manner of [LayerBoundaryTest]: a count that rises fails, so no new Rx arrives
 * while the migration runs, and a count that falls fails too, so this table is lowered in the same
 * change and always says how much is left. When it is empty, replace this test with the absolute
 * rule: no file imports `io.reactivex`.
 *
 * Test code is not counted. The contract suites import Rx on purpose until the last phase.
 */
class RxUsageRatchetTest {

    private val expected = mapOf(
        "app-orature:orature" to 1,
        "app-orature:orature/services" to 1,
        "app-orature:orature/ui/viewmodels" to 10,
        "app-recorder:bttrecorder2/ui" to 1,
        "app-recorder:bttrecorder2/ui/navigation" to 1,
        "app-recorder:bttrecorder2/ui/viewmodels" to 5,
        "shared:otter/common/api/io/zip" to 1,
        "shared:otter/common/api/persistence" to 2,
        "shared:otter/common/api/persistence/config" to 1,
        "shared:otter/common/api/persistence/repositories" to 12,
        "shared:otter/common/data/workbook" to 8,
        "shared:otter/common/domain/audio" to 2,
        "shared:otter/common/domain/collections" to 6,
        "shared:otter/common/domain/content" to 4,
        "shared:otter/common/domain/languages" to 2,
        "shared:otter/common/domain/model" to 1,
        "shared:otter/common/domain/narration" to 5,
        "shared:otter/common/domain/narration/teleprompter" to 1,
        "shared:otter/common/domain/project" to 3,
        "shared:otter/common/domain/project/exporter" to 2,
        "shared:otter/common/domain/project/exporter/resourcecontainer" to 4,
        "shared:otter/common/domain/project/importer" to 7,
        "shared:otter/common/domain/resourcecontainer" to 1,
        "shared:otter/common/domain/resourcecontainer/project" to 1,
        "shared:otter/common/initialization" to 13,
        "shared:otter/common/io/utils" to 1,
        "shared:otter/common/io/zip" to 3,
        "shared:otter/common/persistence/repositories" to 11,
        "shared:otter/common/utils" to 1,
    )

    /**
     * Blocking Rx calls (`blockingGet`, `blockingAwait`, ...) left in each app file. One of these on
     * the main thread freezes the screen, and the main thread is where a ViewModel starts. None is
     * on a repository any more; what is left blocks on a use case or an entity, on an io dispatcher.
     */
    private val expectedBlockingCalls = mapOf(
        "app-orature:OratureChapterReviewViewModel.kt" to 1,
        "app-orature:OratureNarrationViewModel.kt" to 5,
        "app-orature:OratureTranslationViewModel.kt" to 1,
        "app-orature:OratureVerseMarkerViewModel.kt" to 1,
        "app-recorder:ExportProjectViewModel.kt" to 1,
        "app-recorder:ProjectCreationViewModel.kt" to 2,
    )

    private val rxPackages = listOf("io.reactivex", "com.jakewharton.rxrelay2", "kotlinx.coroutines.rx2")

    private val repoRoot: File = run {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        dir
    }

    private fun importsRx(file: File) = file.useLines { lines ->
        lines.map { it.trim() }.any { line -> rxPackages.any { line.startsWith("import $it") } }
    }

    /** `module:package/path` to the number of Rx-importing files in it, over every `*Main` source set. */
    private fun actual(): Map<String, Int> = listOf("shared", "app-orature", "app-recorder").flatMap { module ->
        mainSourceSets(module).flatMap { sourceSet ->
            val root = File(sourceSet, "kotlin/org/bibletranslationtools")
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && importsRx(it) }.map {
                "$module:" + it.parentFile.toRelativeString(root).replace(File.separatorChar, '/')
            }.toList()
        }
    }.groupingBy { it }.eachCount()

    private fun mainSourceSets(module: String): List<File> {
        val sourceSets = File(repoRoot, "$module/src").listFiles { dir -> dir.isDirectory && dir.name.endsWith("Main") }
        assertTrue(!sourceSets.isNullOrEmpty(), "found no main source sets under $module/src")
        return sourceSets.toList()
    }

    /** `module:File.kt` to the number of blocking Rx calls in it, comments left out. */
    private fun actualBlockingCalls(): Map<String, Int> {
        val comments = Regex("""/\*.*?\*/|//[^\n]*""", RegexOption.DOT_MATCHES_ALL)
        val blockingCall = Regex("""\.blocking[A-Z]\w*\(""")
        return listOf("app-orature", "app-recorder").flatMap { module ->
            mainSourceSets(module).flatMap { sourceSet ->
                sourceSet.walkTopDown().filter { it.isFile && it.extension == "kt" }.map {
                    "$module:${it.name}" to blockingCall.findAll(it.readText().replace(comments, "")).count()
                }.filter { it.second > 0 }.toList()
            }
        }.toMap()
    }

    private fun assertRatchet(expected: Map<String, Int>, actual: Map<String, Int>, risen: String, fallen: String) {
        val up = actual.filter { (key, count) -> count > (expected[key] ?: 0) }
        val down = expected.filter { (key, count) -> (actual[key] ?: 0) < count }
        if (up.isNotEmpty()) {
            fail("$risen\n" + up.entries.joinToString("\n") { "  - ${it.key}: ${expected[it.key] ?: 0} -> ${it.value}" })
        }
        if (down.isNotEmpty()) {
            fail("$fallen\n" + down.entries.joinToString("\n") { "  - ${it.key}: ${it.value} -> ${actual[it.key] ?: 0}" })
        }
    }

    @Test
    fun `the number of files importing RxJava only goes down, and this table follows it`() = assertRatchet(
        expected, actual(),
        risen = "New RxJava usage. Write new code with coroutines; do not raise the table to make this pass:",
        fallen = "Fewer files import RxJava here. Lower the table in this test (drop the line at 0):"
    )

    @Test
    fun `the number of blocking Rx calls in the apps only goes down, and this table follows it`() = assertRatchet(
        expectedBlockingCalls, actualBlockingCalls(),
        risen = "New blocking Rx call in an app. Call the suspend function; do not raise the table to make this pass:",
        fallen = "Fewer blocking Rx calls here. Lower the table in this test (drop the line at 0):"
    )
}
