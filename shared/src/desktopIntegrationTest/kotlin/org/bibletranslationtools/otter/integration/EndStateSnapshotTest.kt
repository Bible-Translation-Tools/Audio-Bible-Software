package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.NoStrayErrorsRule
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.junit.Rule
import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The end state of each import, export, create and delete scenario, compared with the one recorded
 * from the RxJava code. A port that leaves different rows or files behind fails here.
 */
class EndStateSnapshotTest {

    @get:Rule
    val noStrayErrors = NoStrayErrorsRule()

    private val environments = mutableListOf<IntegrationEnvironment>()
    private val kept = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        environments.forEach { it.close() }
        kept.forEach { it.delete() }
    }

    private fun environment() = IntegrationEnvironment.create().also { environments += it }.apply { import("en_ulb.zip") }

    /** A French narration project of Jude with one recording, on verse 3. */
    private fun IntegrationEnvironment.judeWithTake(): Collection {
        val project = createProject(
            sourceBook("jud", sourceEditions().single().id), language("fr"),
            mode = ProjectMode.NARRATION, deriveProjectFromVerses = true
        )
        val take = take(recordTake(project, sort = 1, verse = 3))
        File(URI("file:${take.filepath}")).writeText("a recording")
        return project
    }

    /** Every initializer, in the app's order, on an install with nothing in it. */
    @Test
    fun `a fresh startup`() {
        val environment = IntegrationEnvironment.create().also { environments += it }

        environment.koinGet<InitializeApp>().initApp().blockingSubscribe()

        environment.assertSnapshot("fresh-startup")
    }

    @Test
    fun `importing a source`() {
        environment().assertSnapshot("import-source")
    }

    @Test
    fun `creating a narration project`() {
        val environment = environment()
        environment.createProject(
            environment.sourceBook("jud", environment.sourceEditions().single().id), environment.language("fr"),
            mode = ProjectMode.NARRATION, deriveProjectFromVerses = true
        )

        environment.assertSnapshot("create-narration-project")
    }

    @Test
    fun `creating a translation project`() {
        val environment = environment()
        environment.createProject(
            environment.sourceBook("jud", environment.sourceEditions().single().id), environment.language("fr"),
            mode = ProjectMode.TRANSLATION, deriveProjectFromVerses = false
        )

        environment.assertSnapshot("create-translation-project")
    }

    @Test
    fun `backing up a project`() {
        val environment = environment()
        environment.backup(environment.judeWithTake())

        environment.assertSnapshot("export-backup")
    }

    @Test
    fun `importing a backup into another install`() {
        val first = environment()
        val backup = first.backup(first.judeWithTake())
            .copyTo(File.createTempFile("snapshot-backup", ".zip"), overwrite = true).also { kept += it }
        first.close()
        environments -= first

        val second = environment()
        second.import(backup)

        second.assertSnapshot("import-backup")
    }

    @Test
    fun `deleting a project`() {
        val environment = environment()
        environment.judeWithTake()

        environment.deleteAllProjects()

        environment.assertSnapshot("delete-project")
    }
}
