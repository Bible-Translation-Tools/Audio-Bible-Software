package org.bibletranslationtools.otter.common.domain.wacs.usecase

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.fake.FakeWacs
import org.bibletranslationtools.otter.common.domain.wacs.fake.TestAppDirectories
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsChapterIngredient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** [CloneWacsRepo] and [PullChapter] against an in-process host (see [FakeWacs]). */
class PullFromWacsTest {
    private val root = createTempDirectory("wacs-pull").toFile()
    private val wacs = FakeWacs(root)
    private val user = wacs.addUser("translator", "secret")
    private val session = WacsSession().apply { set(user, wacs.host) }
    private val directories = TestAppDirectories(File(root, "app"))
    private val cloneRepo = CloneWacsRepo(wacs.apiFactory, wacs.gitClient, session, WacsConfig(baseUrl = wacs.host), directories)
    private val pullChapter = PullChapter(wacs.gitClient, wacs.lfsClient, session, directories)

    init {
        wacs.addOrg("AudioTranslation")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun audio(chapter: Int) = "chapter $chapter audio".encodeToByteArray()
    private fun audioPath(chapter: Int) = WacsRepoLayout.audioIngredientPath("MRK", chapter, "wav")
    private fun alignmentPath(chapter: Int) = WacsRepoLayout.alignmentIngredientPath("MRK", chapter)
    private fun ingredient(chapter: Int) = WacsChapterIngredient("MRK", chapter, audioPath(chapter), listOf(alignmentPath(chapter)))

    /** A `metadata.json` listing [chapters]' audio and alignment, in the shape [WacsScopeReader] reads. */
    private fun metadata(vararg chapters: Int) = buildJsonObject {
        putJsonArray("languages") { add(buildJsonObject { put("tag", "en") }) }
        putJsonObject("ingredients") {
            chapters.forEach { chapter ->
                putJsonObject(audioPath(chapter)) {
                    put("mimeType", "audio/wav")
                    putJsonObject("scope") { putJsonArray("MRK") { add("$chapter") } }
                }
                putJsonObject(alignmentPath(chapter)) {
                    put("mimeType", "application/json")
                    putJsonObject("scope") { putJsonArray("MRK") { add("$chapter") } }
                }
            }
        }
    }.toString().encodeToByteArray()

    private fun published(owner: String = "AudioTranslation"): ForgejoRepo = wacs.createRepo(
        owner, "audio-en-ulb-mrk",
        files = mapOf(WacsRepoLayout.METADATA_PATH to metadata(1), alignmentPath(1) to "alignment 1".encodeToByteArray()),
        lfsFiles = mapOf(audioPath(1) to audio(1)),
    )

    private fun open(repo: ForgejoRepo) = runBlocking { cloneRepo.open(repo) }

    private fun openFailure(repo: ForgejoRepo) = assertFailsWith<WacsPullException> { open(repo) }.reason

    private fun pullFailure(repo: ForgejoRepo, ingredient: WacsChapterIngredient) = assertFailsWith<WacsPullException> {
        runBlocking { pullChapter.pull(repo, open(repo).workDir, ingredient) }
    }.reason

    @Test
    fun `opening a repo reads what it has without downloading any audio`() {
        val opened = open(published())

        assertEquals(mapOf("MRK" to listOf(ingredient(1))), opened.scope.books)
        assertTrue(wacs.requests.none { it.path.contains("/info/lfs/") }, "no LFS traffic: ${wacs.requests}")
    }

    @Test
    fun `reopening a repo picks up chapters published since`() {
        val repo = published()
        open(repo)
        wacs.pushCommit(
            repo.fullName,
            files = mapOf(WacsRepoLayout.METADATA_PATH to metadata(1, 2), alignmentPath(2) to "alignment 2".encodeToByteArray()),
            lfsFiles = mapOf(audioPath(2) to audio(2)),
        )

        assertEquals(listOf(ingredient(1), ingredient(2)), open(repo).scope.books["MRK"])
    }

    @Test
    fun `a broken cached clone is replaced with a fresh one`() {
        val repo = published()
        File(open(repo).workDir, ".git/objects").deleteRecursively()

        assertEquals(listOf(ingredient(1)), open(repo).scope.books["MRK"])
    }

    @Test
    fun `a repo without a metadata json has nothing to restore`() {
        assertEquals(WacsPullException.Reason.NO_SCOPE, openFailure(wacs.createRepo("AudioTranslation", "audio-en-ulb-mrk")))
    }

    @Test
    fun `the organization's repo and the user's own repo of the same name are cached apart`() {
        val official = open(published("AudioTranslation"))
        val own = open(published("translator"))

        assertNotEquals(official.workDir, own.workDir)
        assertTrue(File(official.workDir, ".git").isDirectory && File(own.workDir, ".git").isDirectory)
    }

    @Test
    fun `pulling a chapter downloads its audio and copies its alignment`() {
        val repo = published()
        val opened = open(repo)

        val pulled = runBlocking { pullChapter.pull(repo, opened.workDir, ingredient(1)) }

        assertContentEquals(audio(1), pulled.audioFile.readBytes())
        assertEquals(listOf("alignment 1"), pulled.companionFiles.map { it.readText() })
    }

    @Test
    fun `a chapter that isn't in the repo can't be pulled`() {
        assertEquals(WacsPullException.Reason.CHAPTER_NOT_FOUND, pullFailure(published(), ingredient(2)))
    }

    @Test
    fun `audio that doesn't match its pointer is rejected`() {
        val repo = published()
        wacs.corruptLfsObject(repo.fullName, FakeWacs.sha256(audio(1)))

        assertEquals(WacsPullException.Reason.INTEGRITY, pullFailure(repo, ingredient(1)))
    }

    @Test
    fun `opening needs a signed-in user`() {
        val repo = published()
        session.clear()

        assertEquals(WacsPullException.Reason.NOT_AUTHENTICATED, openFailure(repo))
    }

    @Test
    fun `a host that can't be reached is a network failure`() {
        val repo = published()
        wacs.unreachable = true

        assertEquals(WacsPullException.Reason.NETWORK, openFailure(repo))
    }
}
