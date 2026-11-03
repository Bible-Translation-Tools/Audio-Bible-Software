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
import kotlin.test.assertNull
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
        "AudioTranslation", "en_ulb",
        files = mapOf(WacsRepoLayout.GITATTRIBUTES_PATH to "${WacsRepoLayout.gitattributesLine("wav")}\n".encodeToByteArray()),
        lfsFiles = mapOf(AUDIO_PATH to audio),
    )

    private fun fork(official: ForgejoRepo): ForgejoRepo = runBlocking {
        wacs.apiFactory.create(wacs.host).forkRepo(official.owner.login, official.name, user.authorizationHeader())
    }

    private fun cloneOf(repo: ForgejoRepo): File = File(root, "clone-${repo.owner.login}").also {
        runBlocking { git.clone(repo.cloneUrl, it, user) }
    }

    /** Writes [text] at [path] and commits just that file. */
    private fun commit(dir: File, path: String, text: String, message: String): String? = runBlocking {
        File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        git.stage(dir, add = listOf(path))
        git.commitStaged(dir, message, "translator", "translator@example.org")
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
    fun `a new branch from upstream is pushed to the fork, and only there`() {
        val official = official()
        val fork = fork(official)
        wacs.pushCommit(official.fullName, mapOf("other.txt" to "merged upstream".encodeToByteArray()))
        val dir = cloneOf(fork)

        val upstreamTip = runBlocking { git.fetchRemote(dir, "upstream", official.cloneUrl, "master", user) }
        runBlocking { git.startBranch(dir, "publish/1", "refs/remotes/upstream/master") }
        commit(dir, "notes.txt", "mine", "Add notes")
        runBlocking { git.push(dir, user, "publish/1", expectedRemote = null) }

        assertEquals(upstreamTip, runBlocking { git.resolve(dir, "refs/remotes/upstream/master") })
        assertEquals(listOf("Add notes", "Another contributor's change", "Initial commit"), wacs.log(fork.fullName, "publish/1"))
        assertEquals(listOf("Initial commit"), wacs.log(fork.fullName), "the fork's master is never used")
    }

    @Test
    fun `nothing staged makes no commit`() {
        val dir = cloneOf(official())

        assertNull(runBlocking { git.commitStaged(dir, "Empty", "translator", "translator@example.org") })
    }

    @Test
    fun `a push whose lease no longer holds is rejected`() {
        val repo = official()
        val dir = cloneOf(repo)
        runBlocking { git.startBranch(dir, "publish/1", "HEAD") }
        commit(dir, "notes.txt", "first", "First")
        runBlocking { git.push(dir, user, "publish/1", expectedRemote = null) }

        // Pushing it as new again: the branch exists now, so the lease fails.
        commit(dir, "notes.txt", "second", "Second")
        val failure = assertFailsWith<Exception> { runBlocking { git.push(dir, user, "publish/1", expectedRemote = null) } }

        assertContains(failure.message.orEmpty(), "Push rejected")
        assertEquals(listOf("First", "Initial commit"), wacs.log(repo.fullName, "publish/1"))
    }

    @Test
    fun `a branch rebuilt with the right lease replaces the old one`() {
        val repo = official()
        val dir = cloneOf(repo)
        runBlocking { git.startBranch(dir, "publish/1", "HEAD") }
        commit(dir, "notes.txt", "first", "First")
        runBlocking { git.push(dir, user, "publish/1", expectedRemote = null) }
        val previous = runBlocking { git.resolve(dir, "publish/1") }

        runBlocking { git.startBranch(dir, "publish/1", "refs/remotes/origin/master") }
        commit(dir, "notes.txt", "rebuilt", "Rebuilt")
        runBlocking { git.push(dir, user, "publish/1", expectedRemote = previous) }

        assertEquals(listOf("Rebuilt", "Initial commit"), wacs.log(repo.fullName, "publish/1"))
    }

    @Test
    fun `files are listed and restored as another revision has them`() {
        val repo = official()
        val dir = cloneOf(repo)
        val original = runBlocking { git.resolve(dir, "HEAD") }!!
        commit(dir, AUDIO_PATH, "a different pointer", "Replace")

        assertEquals(listOf(AUDIO_PATH), runBlocking { git.listFiles(dir, original, "ingredients/MRK") })
        runBlocking { git.restoreFiles(dir, original, listOf(AUDIO_PATH)) }

        assertTrue(LfsPointer.isPointer(File(dir, AUDIO_PATH).readBytes()), "the original pointer is back")
        assertFalse(runBlocking { git.commitStaged(dir, "Restore", "translator", "translator@example.org") } == null)
    }

    @Test
    fun `removing a file stages its deletion`() {
        val dir = cloneOf(official())

        runBlocking { git.stage(dir, add = emptyList(), remove = listOf(AUDIO_PATH)) }
        runBlocking { git.commitStaged(dir, "Remove", "translator", "translator@example.org") }

        assertEquals(emptyList(), runBlocking { git.listFiles(dir, "HEAD", "ingredients/MRK") })
        assertFalse(File(dir, AUDIO_PATH).exists())
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

    private companion object {
        const val AUDIO_PATH = "ingredients/MRK/01.wav"
    }
}
