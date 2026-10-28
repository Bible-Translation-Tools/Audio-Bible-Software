package org.bibletranslationtools.otter.common.domain.wacs.git

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.fake.FakeWacs
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The real JGit client against bare repositories on disk (see [FakeWacs]). */
class JGitWacsGitClientTest {
    private val root = createTempDirectory("jgit-wacs-client").toFile()
    private val wacs = FakeWacs(root)
    private val git = wacs.gitClient
    private val user = wacs.addUser("translator", "secret")
    private val audio = "chapter one audio".encodeToByteArray()

    init {
        wacs.addOrg("AudioTranslation")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun official(): ForgejoRepo = wacs.createRepo(
        "AudioTranslation", "audio-en-ulb-mrk",
        files = mapOf(WacsRepoLayout.GITATTRIBUTES_PATH to "${WacsRepoLayout.gitattributesLine("wav")}\n".encodeToByteArray()),
        lfsFiles = mapOf(AUDIO_PATH to audio),
    )

    private fun cloneOf(repo: ForgejoRepo): File = File(root, "clone-${repo.owner.login}").also {
        runBlocking { git.clone(repo.cloneUrl, it, user) }
    }

    private fun commit(dir: File, path: String, text: String, message: String) = runBlocking {
        File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        git.commit(dir, listOf(path), message, "translator", "translator@example.org")
    }

    @Test
    fun `a clone checks out LFS pointers, never the audio`() {
        val dir = cloneOf(official())

        assertTrue(LfsPointer.isPointer(File(dir, AUDIO_PATH).readBytes()), "the working tree holds the pointer")
        val tracked = runBlocking { git.listLfsPointers(dir) }
        assertEquals(
            listOf(IWacsGitClient.TrackedObject(AUDIO_PATH, LfsPointer(FakeWacs.sha256(audio), audio.size.toLong()))),
            tracked,
        )
    }

    @Test
    fun `a pushed commit is on the remote`() {
        val repo = official()
        val dir = cloneOf(repo)

        commit(dir, "notes.txt", "notes", "Add notes")
        runBlocking { git.push(dir, user) }

        assertEquals("Add notes", wacs.log(repo.fullName).first())
    }

    @Test
    fun `a push the remote has moved past is rejected`() {
        val repo = official()
        val dir = cloneOf(repo)
        wacs.pushCommit(repo.fullName, mapOf("other.txt" to "theirs".encodeToByteArray()))
        commit(dir, "notes.txt", "mine", "Add notes")

        val failure = assertFailsWith<Exception> { runBlocking { git.push(dir, user) } }

        assertContains(failure.message.orEmpty(), "Push rejected")
    }

    @Test
    fun `fetching fast-forwards to the remote's new commits`() {
        val repo = official()
        val dir = cloneOf(repo)
        wacs.pushCommit(repo.fullName, mapOf("other.txt" to "theirs".encodeToByteArray()))

        assertTrue(runBlocking { git.fetchAndFastForward(dir, user) }, "the branch moved")
        assertEquals("theirs", File(dir, "other.txt").readText())
        assertFalse(runBlocking { git.fetchAndFastForward(dir, user) }, "nothing new the second time")
    }

    @Test
    fun `syncing from upstream brings a fork level with the official repo`() {
        val official = official()
        val fork = runBlocking { wacs.apiFactory.create(wacs.host).forkRepo(official.owner.login, official.name, user.authorizationHeader()) }
        wacs.pushCommit(official.fullName, mapOf("other.txt" to "merged upstream".encodeToByteArray()))
        val dir = cloneOf(fork)

        assertTrue(runBlocking { git.syncFromUpstream(dir, official.cloneUrl, user) })
        commit(dir, "notes.txt", "mine", "Add notes")
        runBlocking { git.push(dir, user) }

        assertEquals(listOf("Add notes", "Another contributor's change", "Initial commit"), wacs.log(fork.fullName))
    }

    @Test
    fun `syncing fails when the branch has diverged from upstream`() {
        val official = official()
        val fork = runBlocking { wacs.apiFactory.create(wacs.host).forkRepo(official.owner.login, official.name, user.authorizationHeader()) }
        val dir = cloneOf(fork)
        commit(dir, "notes.txt", "mine", "Add notes")
        wacs.pushCommit(official.fullName, mapOf("other.txt" to "merged upstream".encodeToByteArray()))

        val failure = assertFailsWith<Exception> { runBlocking { git.syncFromUpstream(dir, official.cloneUrl, user) } }

        assertContains(failure.message.orEmpty(), "Cannot fast-forward")
    }

    private companion object {
        const val AUDIO_PATH = "ingredients/MRK/01.wav"
    }
}
