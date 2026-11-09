package org.bibletranslationtools.otter.common.domain.wacs.usecase

import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.ReplayRelay
import io.mockk.mockk
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Content
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
import org.bibletranslationtools.otter.common.domain.audio.EncodeAudioToFlac
import org.bibletranslationtools.otter.common.audio.flac.FlacStreamDecoder
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.fake.FakeWacs
import org.bibletranslationtools.otter.common.domain.wacs.fake.TestTempFiles
import org.bibletranslationtools.otter.common.domain.wacs.fake.chapterWav
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.bibletranslationtools.otter.common.domain.wacs.lfs.Sha256
import org.bibletranslationtools.otter.common.domain.wacs.usecase.PublishChaptersToWacs.Progress
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [PublishChaptersToWacs] against an in-process WACS host and publish pipeline (see [FakeWacs]),
 * publishing a synthesized English ULB Mark workbook, so the official repo is
 * `AudioTranslation/en_ulb` and the user's fork `translator/en_ulb_audio`.
 */
class PublishChaptersToWacsTest {
    private val root = createTempDirectory("wacs-publish").toFile()
    private val wacs = FakeWacs(root)
    private val password = wacs.addUser("translator", "secret")
    private val session = WacsSession().apply { set(wacs.tokenFor("translator"), wacs.host, wacs.pipelineUrl) }
    private val publisher = PublishChaptersToWacs(
        wacs.apiFactory, wacs.gitClient, wacs.lfsClient, session,
        WacsConfig(baseUrl = wacs.host, pipelineUrl = wacs.pipelineUrl), TestTempFiles(root), EncodeAudioToFlac(),
        retryDelaysMs = listOf(0, 0, 0),
    )
    private val takes = (1..3).associateWith { take(it, version = 1) }.toMutableMap()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A take of [chapter]; each [version] has different audio. */
    private fun take(chapter: Int, version: Int) =
        chapterWav(File(root, "takes/mrk_c0${chapter}_t$version.wav"), "mrk", chapter, verses = 2 + version)

    private fun publish(vararg chapters: Int, onProgress: (Progress) -> Unit = {}) =
        runBlocking { publisher.publish(PublishChaptersToWacs.Request(workbook(takes), chapters.toList()), onProgress) }

    private fun failure(vararg chapters: Int = intArrayOf(1), book: Workbook = workbook(takes)) = assertFailsWith<WacsPublishException> {
        runBlocking { publisher.publish(PublishChaptersToWacs.Request(book, chapters.toList())) }
    }

    private fun prepareCalls() = wacs.requests.count { it.host == FakeWacs.PIPELINE_HOST }

    /** [take] as publishing uploads it: encoding is deterministic, so this is the same file. */
    private fun flacOf(take: File): File =
        File(root, "expected/${take.nameWithoutExtension}.flac").apply { parentFile.mkdirs() }
            .also { runBlocking { EncodeAudioToFlac()(take, it) } }

    private fun oidOf(take: File) = Sha256.hex(flacOf(take))

    private fun pointerAt(checkout: File, path: String) = assertNotNull(LfsPointer.parse(File(checkout, path).readBytes()), path)

    private fun audioPath(chapter: Int) = WacsRepoLayout.chapterFilePath("MRK", "en_ulb_mrk_c0$chapter.flac")

    private fun timingPath(chapter: Int) = WacsRepoLayout.chapterFilePath("MRK", "en_ulb_mrk_c0$chapter.json")

    private fun branch(chapter: Int) = "publish/mrk-c0$chapter"

    /** The open or closed pull request from [chapter]'s branch, the latest if there are several. */
    private fun pullFor(chapter: Int) = wacs.pullRequests(OFFICIAL).last { it.head?.ref == branch(chapter) }

    @Test
    fun `publishing sets up the official repo and the user's fork, and opens a pull request`() {
        val result = publish(1)

        assertEquals(OFFICIAL, result.official)
        assertEquals(FORK, result.fork)
        assertEquals(OFFICIAL, wacs.parentOf(FORK))
        val pull = wacs.pullRequests(OFFICIAL).single()
        assertEquals(FORK, pull.head?.repo?.fullName)
        assertEquals("publish/mrk-c01", pull.head?.ref)
        assertEquals("master", pull.base?.ref)
        assertEquals("MRK 1", pull.title)
        assertEquals("- MRK 1", pull.body)
        assertEquals(listOf(PublishChaptersToWacs.PullRequest(pull.number, pull.htmlUrl, listOf("MRK 1"), opened = true)), result.pullRequests)
        assertEquals(listOf("Initial commit"), wacs.log(OFFICIAL), "the official repo changes only by merging")
    }

    @Test
    fun `each chapter is its own branch, commit and pull request`() {
        val result = publish(3, 1, 2)

        assertEquals(listOf("MRK 1", "MRK 2", "MRK 3"), result.pullRequests.map { it.chapters.single() }, "in chapter order")
        assertEquals(listOf("MRK 1", "MRK 2", "MRK 3"), wacs.pullRequests(OFFICIAL).map { it.title })
        assertEquals(setOf("master", branch(1), branch(2), branch(3)), wacs.branches(FORK).toSet())
        for (chapter in 1..3) {
            val commits = wacs.commits(FORK, branch(chapter))
            assertEquals(listOf("MRK $chapter\n\nAudio-Chapter: MRK $chapter", "Initial commit"), commits.map { it.message })
            assertEquals(setOf(audioPath(chapter), timingPath(chapter)), commits.first().changedPaths, "only its own files")
        }
    }

    @Test
    fun `a chapter's commit holds its FLAC's pointer and its timing file`() {
        publish(1)

        val checkout = wacs.checkout(FORK, branch(1))
        val pointer = pointerAt(checkout, audioPath(1))
        val flac = flacOf(takes.getValue(1))
        assertEquals(LfsPointer(Sha256.hex(flac), flac.length()), pointer)
        val uploaded = File(root, "uploaded.flac").apply { writeBytes(assertNotNull(wacs.lfsObject(FORK, pointer.oid), "the audio is in LFS")) }
        assertTrue(FlacStreamDecoder.check(uploaded).lossless, "the audio in LFS is FLAC")
        val timing = File(checkout, timingPath(1)).readText()
        assertContains(timing, "\"media/mrk/en_ulb_mrk_c01.flac\"", message = "the timing names its audio by its path in the repo")
        assertContains(timing, "MRK 1:3", message = "the timing carries the take's markers")
        assertEquals("""{"ingredients":{}}""", File(checkout, WacsRepoLayout.METADATA_PATH).readText(), "metadata.json is never edited")
    }

    @Test
    fun `progress is reported stage by stage, chapter by chapter`() {
        val stages = mutableListOf<Progress>()

        publish(1, 2) { stages += it }

        val chapterStages = { chapter: Int ->
            listOf(
                Progress.PreparingChapter(chapter), Progress.UploadingAudio(chapter, flacOf(takes.getValue(chapter)).length()),
                Progress.Pushing(chapter), Progress.OpeningPullRequest(chapter),
            )
        }
        assertEquals(listOf(Progress.PreparingRepos, Progress.Cloning) + chapterStages(1) + chapterStages(2) + Progress.Done, stages)
    }

    @Test
    fun `a new take of a chapter with an open pull request updates it and opens none`() {
        val first = publish(1, 2).pullRequests
        val chapterTwo = wacs.commits(FORK, branch(2)).first().sha
        takes[1] = take(1, version = 2)

        val second = publish(1).pullRequests.single()

        assertEquals(first[0].copy(opened = false), second)
        assertEquals(2, wacs.pullRequests(OFFICIAL).size, "no new pull request")
        assertEquals(listOf("MRK 1\n\nAudio-Chapter: MRK 1", "Initial commit"), wacs.log(FORK, branch(1)), "rebuilt, not stacked")
        assertEquals(oidOf(takes.getValue(1)), pointerAt(wacs.checkout(FORK, branch(1)), audioPath(1)).oid)
        assertEquals(chapterTwo, wacs.commits(FORK, branch(2)).first().sha, "the other chapter's pull request is left alone")
    }

    @Test
    fun `a chapter whose branch outlived its merged pull request gets a new one`() {
        publish(1)
        wacs.mergePullRequest(OFFICIAL, 1)
        takes[1] = take(1, version = 2)

        // The fork's publish/mrk-c01 is still at the merged take, so this push needs it as its lease.
        val result = publish(1).pullRequests.single()

        assertEquals(PublishChaptersToWacs.PullRequest(2, pullFor(1).htmlUrl, listOf("MRK 1"), opened = true), result)
        assertEquals(listOf("closed", "open"), wacs.pullRequests(OFFICIAL).map { it.state })
        assertEquals(oidOf(takes.getValue(1)), pointerAt(wacs.checkout(FORK, branch(1)), audioPath(1)).oid)
    }

    @Test
    fun `chapter pull requests merge in any order`() {
        publish(1, 2, 3)

        wacs.mergePullRequest(OFFICIAL, 1)
        wacs.mergePullRequest(OFFICIAL, 3)

        val official = wacs.checkout(OFFICIAL, "master")
        assertEquals(oidOf(takes.getValue(1)), pointerAt(official, audioPath(1)).oid)
        assertEquals(oidOf(takes.getValue(3)), pointerAt(official, audioPath(3)).oid)
        assertTrue(!File(official, audioPath(2)).exists(), "chapter 2 isn't merged yet")
    }

    @Test
    fun `a chapter already on the official repo as is is skipped`() {
        publish(1)
        wacs.mergePullRequest(OFFICIAL, 1)

        val result = publish(1, 2)

        assertEquals(listOf("MRK 2"), result.pullRequests.map { it.chapters.single() })
        assertEquals(listOf("closed", "open"), wacs.pullRequests(OFFICIAL).map { it.state })
    }

    @Test
    fun `prepare is retried while the pipeline fails, then publishing goes ahead`() {
        wacs.fail("POST", "/v1/publish/prepare", 503, host = FakeWacs.PIPELINE_HOST, times = 2)

        publish(1)

        assertEquals(3, prepareCalls())
    }

    @Test
    fun `a pipeline that keeps failing is unavailable`() {
        wacs.fail("POST", "/v1/publish/prepare", 503, host = FakeWacs.PIPELINE_HOST)

        assertEquals(WacsPublishException.Reason.PIPELINE_UNAVAILABLE, failure().reason)
        assertEquals(4, prepareCalls(), "the first try and three retries")
    }

    @Test
    fun `the pipeline's refusal is final and says why`() {
        wacs.knownLanguages = setOf("fr")

        val refusal = failure()

        assertEquals(WacsPublishException.Reason.PIPELINE_REJECTED, refusal.reason)
        assertEquals("\"en\" is not a known IETF language tag", refusal.detail)
        assertEquals(1, prepareCalls(), "a 4xx isn't retried")
    }

    @Test
    fun `the pipeline takes a token, not a password`() {
        session.set(password, wacs.host, wacs.pipelineUrl)

        assertEquals(WacsPublishException.Reason.AUTH_REJECTED, failure().reason)
    }

    @Test
    fun `nothing is pushed when the audio can't be uploaded`() {
        wacs.fail("PUT", "/$FORK.git/info/lfs/objects/${oidOf(takes.getValue(1))}", 500)

        assertEquals(WacsPublishException.Reason.NETWORK, failure().reason)
        assertEquals(listOf("master"), wacs.branches(FORK), "no pointer without its audio")
        assertEquals(emptyList(), wacs.pullRequests(OFFICIAL))
    }

    @Test
    fun `publishing needs a signed-in user`() {
        session.clear()

        assertEquals(WacsPublishException.Reason.NOT_AUTHENTICATED, failure().reason)
    }

    @Test
    fun `a chapter with no selected take stops publishing before anything is sent`() {
        assertEquals(WacsPublishException.Reason.NO_AUDIO, failure(1, book = workbook(emptyMap())).reason)
        assertEquals(emptyList(), wacs.requests)
    }

    @Test
    fun `a host that can't be reached leaves the pipeline unavailable`() {
        wacs.unreachable = true

        assertEquals(WacsPublishException.Reason.PIPELINE_UNAVAILABLE, failure().reason)
    }

    /** English ULB Mark, with chapters 1 to 3; a chapter in [selected] has that file as its selected take. */
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
            chapters = Observable.fromIterable((1..3).map { chapter(it, selected[it]) }),
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
        const val OFFICIAL = "AudioTranslation/en_ulb"
        const val FORK = "translator/en_ulb_audio"
    }
}
