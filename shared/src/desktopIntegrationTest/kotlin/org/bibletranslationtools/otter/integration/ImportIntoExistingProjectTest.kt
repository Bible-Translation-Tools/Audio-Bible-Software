package org.bibletranslationtools.otter.integration

import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.importer.ImportCallbackParameter
import org.bibletranslationtools.otter.common.domain.project.importer.ImportOptions
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Importing a project that is already on this device: an imported recording comes in as a new take,
 * renamed when its number is taken, and never overwrites one already there.
 */
class ImportIntoExistingProjectTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    /** A French Acts project with a recording on 19:41, and its backup. */
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

    private fun fileOf(path: String) = File(URI("file:$path"))

    @Test
    fun `re-importing a project's own backup adds no duplicate takes`() {
        val (environment, project, backup) = setup()

        environment.import(backup)

        val takes = environment.takesOn(project, 19, 41)
        assertEquals(1, takes.size, "the same recording isn't added again")
        assertEquals("local recording", fileOf(takes.single().filepath).readText())
    }

    @Test
    fun `a different recording under the same name comes in as a new take and overwrites nothing`() {
        val (environment, project, backup) = setup()
        val local = environment.takesOn(project, 19, 41).single()
        val entry = environment.zipEntries(backup).single { it.endsWith("_v41_t1.wav") }
        val imported = environment.withEntryReplaced(backup, entry, "imported recording".toByteArray())

        environment.import(imported)

        val takes = environment.takesOn(project, 19, 41)
        assertEquals(listOf(1, 2), takes.map { it.number })
        assertEquals("local recording", fileOf(takes[0].filepath).readText(), "the local take is untouched")
        assertEquals("imported recording", fileOf(takes[1].filepath).readText())
        assertNotEquals(takes[0].filepath, takes[1].filepath)
        assertEquals(true, takes[1].filename.endsWith("_t2.wav"), takes[1].filename)
        assertEquals(local.id, takes[0].id)
        assertEquals(takes[1].id, environment.selectedTakeOn(project, 19, 41), "the backup's selection is applied")
    }

    /**
     * KNOWN GAP, documented rather than fixed here: Orature answers the importer's "which chapters?"
     * request for an existing project with an overwrite yes/no and no chapters, which the importer
     * reads as the user cancelling. When Orature's import dialog lets the user pick chapters, this
     * test fails; turn it into a test of that.
     */
    @Test
    fun `an answer without chapters, as Orature gives today, aborts the import`() {
        val (environment, _, backup) = setup()
        val orature = object : ProjectImporterCallback {
            override fun onRequestUserInput(): Single<ImportOptions> = Single.just(ImportOptions(confirmed = true))
            override fun onRequestUserInput(parameter: ImportCallbackParameter) = onRequestUserInput()
            override fun onNotifyProgress(localizeKey: String?, message: String?, percent: Double?) = Unit
            override fun onNotifySuccess(language: String?, project: String?, workbookDescriptor: WorkbookDescriptor?) = Unit
            override fun onError(filePath: String) = Unit
        }

        assertEquals(ImportResult.ABORTED, environment.importer.import(backup, orature).blockingGet())
    }
}
