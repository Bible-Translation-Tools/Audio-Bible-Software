package org.bibletranslationtools.otter.common.domain.wacs.usecase

import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.ReplayRelay
import io.mockk.mockk
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.AssociatedTranslation
import org.bibletranslationtools.otter.common.data.workbook.Book
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.fake.FakeWacs
import org.bibletranslationtools.otter.common.domain.wacs.fake.TestTempFiles
import org.bibletranslationtools.otter.common.domain.wacs.fake.chapterWav
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsScopeReader
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.bibletranslationtools.otter.common.domain.wacs.lfs.Sha256
import org.bibletranslationtools.otter.common.domain.wacs.usecase.PublishChapterToWacs.Progress
import org.bibletranslationtools.scriptureburrito.BURRITO_JSON
import org.bibletranslationtools.scriptureburrito.MetadataSchema
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [PublishChapterToWacs] against an in-process host (see [FakeWacs]), publishing a synthesized
 * English ULB Mark workbook, so the repo is `audio-en-ulb-mrk`.
 */
class PublishChapterToWacsTest {
    private val root = createTempDirectory("wacs-publish").toFile()
    private val wacs = FakeWacs(root)
    private val user = wacs.addUser("translator", "secret")
    private val session = WacsSession().apply { set(user, wacs.host) }
    private val publisher = PublishChapterToWacs(
        wacs.apiFactory, wacs.gitClient, wacs.lfsClient, session, WacsConfig(baseUrl = wacs.host), TestTempFiles(root),
    )
    private val takes = (1..2).associateWith { chapterWav(File(root, "takes/mrk_c0${it}_t1.wav"), "mrk", it) }
    private val workbook = workbook(takes)

    init {
        wacs.addOrg("AudioTranslation")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun official() = wacs.createRepo("AudioTranslation", REPO)

    private fun publish(chapter: Int, onProgress: (Progress) -> Unit = {}) =
        runBlocking { publisher.publish(PublishChapterToWacs.Request(workbook, chapter), onProgress) }

    private fun failure(chapter: Int = 1, book: Workbook = workbook) = assertFailsWith<WacsPublishException> {
        runBlocking { publisher.publish(PublishChapterToWacs.Request(book, chapter)) }
    }.reason

    @Test
    fun `a chapter is published to the user's fork of the official repo`() {
        official()

        val result = publish(1)

        assertEquals("translator/$REPO", result.forkFullName)
        assertTrue(result.contributesToOfficial)
        assertTrue(assertNotNull(wacs.repo("translator/$REPO")).fork)
        assertEquals(listOf("Publish MRK 1", "Initial commit"), wacs.log("translator/$REPO"))
        assertEquals(listOf("Initial commit"), wacs.log("AudioTranslation/$REPO"), "the official repo changes only through the pull request")
    }

    @Test
    fun `the fork holds the pointer, the alignment and the metadata, and the host holds the audio`() {
        official()
        publish(1)
        val checkout = wacs.checkout("translator/$REPO")
        val audioPath = WacsRepoLayout.audioIngredientPath("MRK", 1, "wav")

        val pointer = assertNotNull(LfsPointer.parse(File(checkout, audioPath).readBytes()), "a pointer, not audio, is committed")
        assertEquals(LfsPointer(Sha256.hex(takes.getValue(1)), takes.getValue(1).length()), pointer)
        assertContentEquals(takes.getValue(1).readBytes(), wacs.lfsObject("translator/$REPO", pointer.oid))
        assertTrue(File(checkout, WacsRepoLayout.alignmentIngredientPath("MRK", 1)).length() > 0)
        assertContains(File(checkout, WacsRepoLayout.GITATTRIBUTES_PATH).readText(), WacsRepoLayout.gitattributesLine("wav"))

        val metadataFile = File(checkout, WacsRepoLayout.METADATA_PATH)
        val metadata = BURRITO_JSON.decodeFromString(MetadataSchema.serializer(), metadataFile.readText())
        assertTrue(metadata.ingredients.containsKey(audioPath))
        val scope = WacsScopeReader.read(metadataFile)
        assertEquals(listOf(1), scope.books["MRK"]?.map { it.chapterNumber }, "a restore can read what was published")
    }

    @Test
    fun `progress is reported stage by stage`() {
        official()
        val stages = mutableListOf<Progress>()

        publish(1) { stages += it }

        assertEquals(
            listOf(
                Progress.CheckingOfficialRepo, Progress.Forking, Progress.Cloning, Progress.SyncingUpstream,
                Progress.PreparingFiles, Progress.Committing, Progress.UploadingAudio(takes.getValue(1).length()),
                Progress.Pushing, Progress.Done,
            ),
            stages,
        )
    }

    @Test
    fun `a session's chapters share one fork and one pull request`() {
        official()

        val first = publish(1)
        val opened = runBlocking { publisher.openContributionPullRequest(first) }
        val second = publish(2)
        val reused = runBlocking { publisher.openContributionPullRequest(second) }

        assertFalse(opened.reused)
        assertTrue(reused.reused)
        assertEquals(opened.number, reused.number)
        assertEquals(listOf(opened.number), wacs.pullRequests("AudioTranslation/$REPO").map { it.number })
        assertEquals(listOf("Publish MRK 2", "Publish MRK 1", "Initial commit"), wacs.log("translator/$REPO"))
    }

    @Test
    fun `a fork behind the official repo is brought level before publishing`() {
        val official = official()
        runBlocking { wacs.apiFactory.create(wacs.host).forkRepo(official.owner.login, REPO, user.authorizationHeader()) }
        wacs.pushCommit(official.fullName, mapOf("README.md" to "# merged since\n".encodeToByteArray()))

        publish(1)

        assertEquals(
            listOf("Publish MRK 1", "Another contributor's change", "Initial commit"),
            wacs.log("translator/$REPO"),
        )
    }

    @Test
    fun `a fork that has diverged from the official repo is a conflict`() {
        official()
        publish(1)
        // The user's chapter 1 isn't merged, and someone else's change is.
        wacs.pushCommit("AudioTranslation/$REPO", mapOf("README.md" to "# merged since\n".encodeToByteArray()))

        assertEquals(WacsPublishException.Reason.CONFLICT, failure(chapter = 2))
        assertEquals(listOf("Publish MRK 1", "Initial commit"), wacs.log("translator/$REPO"))
    }

    @Test
    fun `with no official repo, the chapter goes to a repo of the user's own`() {
        val result = publish(1)

        assertFalse(result.contributesToOfficial)
        assertEquals("translator/$REPO", result.forkFullName)
        assertFalse(assertNotNull(wacs.repo("translator/$REPO")).fork)
        assertEquals(listOf("Publish MRK 1", "Initial commit"), wacs.log("translator/$REPO"))
        assertTrue(wacs.requests.none { it.path.endsWith("/forks") }, "nothing to fork")
    }

    @Test
    fun `publishing again to the user's own repo adds to it`() {
        publish(1)
        publish(2)

        assertEquals(listOf("Publish MRK 2", "Publish MRK 1", "Initial commit"), wacs.log("translator/$REPO"))
    }

    @Test
    fun `nothing is pushed when the audio can't be uploaded`() {
        official()
        wacs.fail("PUT", "/translator/$REPO.git/info/lfs/objects/${Sha256.hex(takes.getValue(1))}", 500)

        assertEquals(WacsPublishException.Reason.NETWORK, failure())
        assertEquals(listOf("Initial commit"), wacs.log("translator/$REPO"), "no pointer without its audio")
    }

    @Test
    fun `publishing needs a signed-in user`() {
        session.clear()

        assertEquals(WacsPublishException.Reason.NOT_AUTHENTICATED, failure())
    }

    @Test
    fun `a rejected password is reported as such`() {
        session.set(WacsCredential.Basic("translator", "changed"), wacs.host)

        assertEquals(WacsPublishException.Reason.AUTH_REJECTED, failure())
    }

    @Test
    fun `a chapter with no selected take has no audio to publish`() {
        official()

        assertEquals(WacsPublishException.Reason.NO_AUDIO, failure(book = workbook(emptyMap())))
    }

    @Test
    fun `a host that can't be reached is a network failure`() {
        wacs.unreachable = true

        assertEquals(WacsPublishException.Reason.NETWORK, failure())
    }

    /** English ULB Mark, with chapters 1 and 2; a chapter in [selected] has that file as its selected take. */
    private fun workbook(selected: Map<Int, File>): Workbook {
        val english = Language(slug = "en", name = "English", anglicizedName = "English", direction = "ltr", isGateway = true, region = "")
        val ulb = ResourceMetadata(
            conformsTo = "0.2", creator = "test", description = "", format = "text/usfm", identifier = "ulb",
            issued = LocalDate.now(), language = english, modified = LocalDate.now(), publisher = "test",
            subject = "Bible", type = ContainerType.Book, title = "Unlocked Literal Bible", version = "1",
            license = "CC BY-SA 4.0", path = File("."),
        )
        val book = Book(
            collectionId = 1, sort = 1, slug = "mrk", title = "Mark", label = "Mark",
            chapters = Observable.fromIterable((1..2).map { chapter(it, selected[it]) }),
            resourceMetadata = ulb, linkedResources = emptyList(), modifiedTs = null, subtreeResources = emptyList(),
        )
        return Workbook(
            directoryProvider = mockk<IDirectoryProvider>(relaxed = true),
            source = book,
            target = book,
            translation = AssociatedTranslation(BehaviorRelay.createDefault(1.0), BehaviorRelay.createDefault(1.0)),
        )
    }

    private fun chapter(number: Int, take: File?): Chapter {
        val audio = AssociatedAudio(ReplayRelay.create())
        take?.let {
            val selected = Take(name = it.name, file = it, number = 1, format = MimeType.WAV, createdTimestamp = LocalDate.now())
            audio.insertTake(selected)
            audio.selectTake(selected)
        }
        return Chapter(
            sort = number,
            title = "Chapter $number",
            label = "$number",
            audio = audio,
            resources = emptyList(),
            subtreeResources = emptyList(),
            lazychunks = lazy { BehaviorRelay.createDefault<List<Chunk>>(emptyList()) },
            chunkCount = Single.just(0),
            addChunk = { _: List<Content> -> Completable.complete() },
            reset = { Completable.complete() },
        )
    }

    private companion object {
        const val REPO = "audio-en-ulb-mrk"
    }
}
