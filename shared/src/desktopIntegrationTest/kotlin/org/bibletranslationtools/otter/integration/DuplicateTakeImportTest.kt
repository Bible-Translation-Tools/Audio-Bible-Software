package org.bibletranslationtools.otter.integration

import io.reactivex.Single
import io.reactivex.functions.Consumer
import io.reactivex.plugins.RxJavaPlugins
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.importer.ImportCallbackParameter
import org.bibletranslationtools.otter.common.domain.project.importer.ImportOptions
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.junit.Assume.assumeFalse
import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A backup holding the same take in both of its take folders: the app's takes folder and the
 * manifest project's own. Older exports wrote a selected chapter take to each.
 *
 * Takes are staged and then moved into the project one by one. When both copies were staged to
 * the same path, the first move took the file and the second found nothing: a NoSuchFileException
 * on a subscriber with no error handler, which went to RxJava's global handler while the import
 * still reported SUCCESS. These tests watch that handler, so they fail on that behaviour rather
 * than passing because the first copy happened to land.
 */
class DuplicateTakeImportTest {
    private var env: IntegrationEnvironment? = null
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousErrorHandler: Consumer<in Throwable>? = null

    @BeforeTest
    fun watchRxErrorHandler() {
        previousErrorHandler = RxJavaPlugins.getErrorHandler()
        RxJavaPlugins.setErrorHandler { uncaught += it }
    }

    @AfterTest
    fun tearDown() {
        RxJavaPlugins.setErrorHandler(previousErrorHandler)
        env?.close()
    }

    private fun setup(): Triple<IntegrationEnvironment, Collection, File> {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val project = environment.createProject(
            environment.sourceBook("act", environment.sourceEditions().single().id), environment.language("fr"),
            mode = ProjectMode.NARRATION, deriveProjectFromVerses = true
        )
        val take = environment.take(environment.recordTake(project, sort = 19, verse = 41))
        fileOf(take.filepath).writeText("local recording")
        return Triple(environment, project, environment.backup(project))
    }

    /**
     * [backup] with its 19:41 take also in the manifest project's folder, holding [bytes] or, by
     * default, the same bytes as the copy in the takes folder.
     */
    private fun withTakeInBothFolders(environment: IntegrationEnvironment, backup: File, bytes: ByteArray? = null): File {
        val entry = environment.zipEntries(backup).single { it.endsWith("_v41_t1.wav") }
        assertTrue(entry.startsWith("${RcConstants.TAKE_DIR}/"), "expected the take in the takes folder: $entry")
        val manifest = environment.entryBytes(backup, "manifest.yaml").decodeToString()
        assertTrue(RcConstants.MEDIA_DIR in manifest, "the manifest's project folder is ${RcConstants.MEDIA_DIR}")
        val inProjectFolder = "${RcConstants.MEDIA_DIR}/${entry.removePrefix("${RcConstants.TAKE_DIR}/")}"
        return environment.withEntryAdded(backup, inProjectFolder, bytes ?: environment.entryBytes(backup, entry))
    }

    private fun fileOf(path: String) = File(URI("file:$path"))

    @Test
    fun `restoring it places the take once and selects it`() {
        val (environment, _, backup) = setup()
        val doubled = withTakeInBothFolders(environment, backup)
        environment.deleteAllProjects()
        val callback = TakeCounts()

        assertEquals(ImportResult.SUCCESS, environment.importer.import(doubled, callback).blockingGet())

        val restored = environment.derivedProjects().single { it.slug == "act" }
        val takes = environment.takesOn(restored, 19, 41)
        assertEquals(1, takes.size, "the second copy isn't added as another take")
        assertEquals("local recording", fileOf(takes.single().filepath).readText())
        assertEquals(takes.single().id, environment.selectedTakeOn(restored, 19, 41))
        assertEquals(Triple(false, 1, 0), callback.takes, "a second copy in the same backup isn't already there")
        assertEquals(emptyList(), uncaught.toList())
    }

    @Test
    fun `re-importing it finds its one recording already there, once`() {
        val (environment, project, backup) = setup()
        val doubled = withTakeInBothFolders(environment, backup)
        val callback = TakeCounts()

        assertEquals(ImportResult.SUCCESS, environment.importer.import(doubled, callback).blockingGet())

        val takes = environment.takesOn(project, 19, 41)
        assertEquals(1, takes.size)
        assertEquals("local recording", fileOf(takes.single().filepath).readText())
        assertEquals(Triple(true, 0, 1), callback.takes, "counted per recording, not per copy")
        assertEquals(emptyList(), uncaught.toList())
    }

    @Test
    fun `a different recording under that name in the other folder comes in as a new take`() {
        val (environment, project, backup) = setup()
        val local = environment.takesOn(project, 19, 41).single()
        val doubled = withTakeInBothFolders(environment, backup, "imported recording".toByteArray())
        val callback = TakeCounts()

        assertEquals(ImportResult.SUCCESS, environment.importer.import(doubled, callback).blockingGet())

        val takes = environment.takesOn(project, 19, 41)
        assertEquals(listOf(1, 2), takes.map { it.number }, "neither copy overwrote the other")
        assertEquals(local.id, takes[0].id)
        assertEquals("local recording", fileOf(takes[0].filepath).readText())
        assertEquals("imported recording", fileOf(takes[1].filepath).readText())
        assertEquals(takes[1].id, environment.selectedTakeOn(project, 19, 41), "the backup's selection is applied")
        assertEquals(Triple(true, 1, 1), callback.takes)
        assertEquals(emptyList(), uncaught.toList())
    }

    @Test
    fun `a take that can't be placed fails the import instead of reporting success`() {
        val (environment, project, backup) = setup()
        val chapterFolder = fileOf(environment.takesOn(project, 19, 41).single().filepath).parentFile
        val entry = environment.zipEntries(backup).single { it.endsWith("_v41_t1.wav") }
        val imported = environment.withEntryReplaced(backup, entry, "imported recording".toByteArray())

        chapterFolder.setWritable(false)
        try {
            assumeFalse("this user can write into read-only folders (root?)", chapterFolder.canWrite())
            assertEquals(ImportResult.FAILED, environment.importer.import(imported).blockingGet())
        } finally {
            chapterFolder.setWritable(true)
        }
        assertEquals(emptyList(), uncaught.toList(), "the failure came back through the import, not the global handler")
    }

    /** A callback that keeps what the importer reports about takes. */
    private class TakeCounts : ProjectImporterCallback {
        var takes: Triple<Boolean, Int, Int>? = null
        override fun onRequestUserInput(): Single<ImportOptions> = Single.just(ImportOptions(confirmed = true))
        override fun onRequestUserInput(parameter: ImportCallbackParameter) = onRequestUserInput()
        override fun onNotifyProgress(localizeKey: String?, message: String?, percent: Double?) = Unit
        override fun onNotifySuccess(language: String?, project: String?, workbookDescriptor: WorkbookDescriptor?) = Unit
        override fun onNotifyTakesImported(merged: Boolean, added: Int, alreadyThere: Int) {
            takes = Triple(merged, added, alreadyThere)
        }
        override fun onError(filePath: String) = Unit
    }
}
