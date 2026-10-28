package org.bibletranslationtools.otter.integration.wacs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withTimeout
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.fake.FakeWacs
import org.bibletranslationtools.otter.common.domain.wacs.fake.chapterWav
import org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsChapterIngredient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsTakeProvenance
import org.bibletranslationtools.otter.common.domain.wacs.lfs.Sha256
import org.bibletranslationtools.otter.common.domain.wacs.usecase.CloneWacsRepo
import org.bibletranslationtools.otter.common.domain.wacs.usecase.CreateProjectFromWacs
import org.bibletranslationtools.otter.common.domain.wacs.usecase.PublishChapterToWacs
import org.bibletranslationtools.otter.common.domain.wacs.usecase.RestoreChapterFromWacs
import org.bibletranslationtools.otter.common.domain.wacs.usecase.RestoreChapterFromWacs.SelectionPolicy
import org.bibletranslationtools.otter.common.domain.wacs.usecase.WacsPullException
import org.bibletranslationtools.otter.integration.IntegrationEnvironment
import org.koin.dsl.module
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Publishing a recorded chapter and restoring it, through the production Koin graph and a real
 * database, with an in-process WACS host (see [FakeWacs]) in place of the network.
 *
 * The project is English ULB Mark, narrated, so its repo is `audio-en-ulb-mrk`. No official repo
 * exists, so publishing goes to the user's own.
 */
class WacsRestoreTest {
    private val root = createTempDirectory("wacs-restore").toFile()
    private val wacs = FakeWacs(root)
    private val user = wacs.addUser("translator", "secret")
    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        root.deleteRecursively()
    }

    private inline fun <reified T : Any> IntegrationEnvironment.component(): T = get(T::class)

    private fun environment(): IntegrationEnvironment {
        val wacsHost = module {
            single { WacsConfig(baseUrl = wacs.host) }
            single { wacs.apiFactory }
            single { wacs.lfsClient }
            single<IWacsGitClient> { wacs.gitClient }
        }
        return IntegrationEnvironment.create(wacsHost).also { env = it }.import("en_ulb.zip").apply {
            component<WacsSession>().set(user, wacs.host)
        }
    }

    private fun IntegrationEnvironment.markProject(): Collection =
        createProject(sourceBook("mrk"), language("en"), ProjectMode.NARRATION, deriveProjectFromVerses = true)

    private fun IntegrationEnvironment.descriptorOf(project: Collection): WorkbookDescriptor = runBlocking {
        workbookDescriptorRepository.getAllSuspend(computeSourceAudio = false).single { it.targetCollection.id == project.id }
    }

    private fun IntegrationEnvironment.workbookOf(project: Collection): Workbook =
        descriptorOf(project).let { workbookRepository.get(it.sourceCollection, it.targetCollection) }

    private fun Workbook.chapter(number: Int): Chapter = runBlocking { target.chapters.toList().await().single { it.sort == number } }

    /** Records chapter [number] whole, as the recorder does, and waits until it's the selected take. */
    private fun IntegrationEnvironment.recordChapter(project: Collection, number: Int): Take = runBlocking {
        val workbook = workbookOf(project)
        val chapter = workbook.chapter(number)
        val audio = chapterWav(File(root, "recorded/mrk_c$number.wav"), "mrk", number)
        val take = saveAudioAsNewTake.execute(workbook = workbook, chapter = chapter, chunk = null, recordable = chapter, audioFile = audio)
        withTimeout(TIMEOUT_MS) { chapter.audio.selectedFlow.first { it.value == take } }
        take
    }

    private fun IntegrationEnvironment.publish(project: Collection, chapter: Int) = runBlocking {
        component<PublishChapterToWacs>().publish(PublishChapterToWacs.Request(workbookOf(project), chapter))
    }

    private fun IntegrationEnvironment.open(repo: ForgejoRepo = wacs.repo("translator/$REPO")!!) =
        runBlocking { component<CloneWacsRepo>().open(repo) }

    private fun markers(file: File) = OratureAudioFile(file).getMarkers().map { it.label }

    @Test
    fun `a published chapter makes a new project when the old one is gone`() {
        val environment = environment()
        val project = environment.markProject()
        val recorded = environment.recordChapter(project, 1)
        environment.publish(project, 1)
        val oid = Sha256.hex(recorded.file)
        val publishedMarkers = markers(recorded.file)
        environment.deleteAllProjects()

        val opened = environment.open()
        val result = runBlocking { environment.component<CreateProjectFromWacs>().create(opened.repo, opened.workDir, opened.scope) }

        val target = result.descriptor.targetCollection
        assertEquals(REPO, WacsRepoLayout.repoNameOrNull(result.descriptor))
        assertEquals(listOf(target.id), environment.derivedProjects().map { it.id })
        val restored = assertIs<CreateProjectFromWacs.ChapterOutcome.Restored>(result.chapterOutcomes[1])
        assertTrue(restored.selected)
        assertEquals(oid, WacsTakeProvenance.read(restored.take.file), "the take remembers which published audio it is")
        assertEquals(publishedMarkers, markers(restored.take.file))
    }

    @Test
    fun `restoring into the project that has the recording adds it beside the original, once`() {
        val environment = environment()
        val project = environment.markProject()
        val original = environment.recordChapter(project, 1)
        environment.publish(project, 1)
        val opened = environment.open()
        val ingredient = opened.scope.books.getValue("MRK").single()
        val restore = environment.component<RestoreChapterFromWacs>()
        fun audioDownloads() = wacs.requests.count { it.method == "GET" && it.path.contains("/info/lfs/objects/") }

        val first = runBlocking {
            restore.restore(environment.descriptorOf(project), opened.repo, opened.workDir, ingredient, SelectionPolicy.KEEP_CURRENT)
        }
        val downloads = audioDownloads()
        val second = runBlocking {
            restore.restore(environment.descriptorOf(project), opened.repo, opened.workDir, ingredient, SelectionPolicy.KEEP_CURRENT)
        }

        assertFalse(assertIs<RestoreChapterFromWacs.Outcome.Restored>(first).selected)
        assertEquals(RestoreChapterFromWacs.Outcome.AlreadyPresent, second)
        assertEquals(downloads, audioDownloads(), "nothing downloaded the second time")
        val chapter = environment.workbookOf(project).chapter(1)
        assertEquals(2, chapter.audio.getAllTakes().count { !it.isDeleted() })
        assertEquals(original, chapter.audio.getSelectedTake(), "the user's selection is kept")
    }

    @Test
    fun `by default the restored take becomes the selected one`() {
        val environment = environment()
        val project = environment.markProject()
        environment.recordChapter(project, 1)
        environment.publish(project, 1)
        val opened = environment.open()

        val outcome = runBlocking {
            environment.component<RestoreChapterFromWacs>()
                .restore(environment.descriptorOf(project), opened.repo, opened.workDir, opened.scope.books.getValue("MRK").single())
        }

        val restored = assertIs<RestoreChapterFromWacs.Outcome.Restored>(outcome)
        assertTrue(restored.selected)
        val chapter = environment.workbookOf(project).chapter(1)
        runBlocking { withTimeout(TIMEOUT_MS) { chapter.audio.selectedFlow.first { it.value == restored.take } } }
    }

    @Test
    fun `a repo for another book isn't restored into the project`() {
        val environment = environment()
        val project = environment.markProject()
        val titus = wacs.createRepo("translator", "audio-en-ulb-tit")
        val ingredient = WacsChapterIngredient("TIT", 1, WacsRepoLayout.audioIngredientPath("TIT", 1, "wav"), emptyList())

        val failure = assertFailsWith<WacsPullException> {
            runBlocking {
                environment.component<RestoreChapterFromWacs>()
                    .restore(environment.descriptorOf(project), titus, root, ingredient)
            }
        }

        assertEquals(WacsPullException.Reason.NO_MATCHING_PROJECT, failure.reason)
    }

    @Test
    fun `a repo whose language doesn't match its name makes no project`() {
        val environment = environment()
        val audioPath = WacsRepoLayout.audioIngredientPath("MRK", 1, "wav")
        val metadata = """
            {"languages": [{"tag": "fr"}],
             "ingredients": {"$audioPath": {"mimeType": "audio/wav", "scope": {"MRK": ["1"]}}}}
        """.trimIndent()
        wacs.createRepo(
            "translator", REPO,
            files = mapOf(WacsRepoLayout.METADATA_PATH to metadata.encodeToByteArray()),
            lfsFiles = mapOf(audioPath to "chapter one".encodeToByteArray()),
        )
        val opened = environment.open()

        val failure = assertFailsWith<WacsPullException> {
            runBlocking { environment.component<CreateProjectFromWacs>().create(opened.repo, opened.workDir, opened.scope) }
        }

        assertEquals(WacsPullException.Reason.SOURCE_UNAVAILABLE, failure.reason)
        assertEquals(emptyList(), environment.derivedProjects())
    }

    private companion object {
        const val REPO = "audio-en-ulb-mrk"
        const val TIMEOUT_MS = 10_000L
    }
}
