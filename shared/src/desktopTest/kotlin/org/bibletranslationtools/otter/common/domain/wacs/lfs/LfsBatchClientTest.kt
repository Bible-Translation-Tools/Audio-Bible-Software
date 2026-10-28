package org.bibletranslationtools.otter.common.domain.wacs.lfs

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.fake.FakeWacs
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hand-rolled LFS Batch client against an in-process host (see [FakeWacs]). */
class LfsBatchClientTest {
    private val root = createTempDirectory("lfs-batch-client").toFile()
    private val wacs = FakeWacs(root)
    private val user = wacs.addUser("translator", "secret")
    private val repo = wacs.createRepo("translator", "audio-en-ulb-mrk")
    private val lfsBase = "${repo.cloneUrl}/info/lfs"
    private val lfs = wacs.lfsClient

    private val audio = File(root, "take.wav").apply { writeText("chapter one audio") }
    private val pointer = LfsPointer(Sha256.hex(audio), audio.length())

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun transfers(method: String) = wacs.requests.filter { it.method == method && it.path.endsWith("/objects/${pointer.oid}") }

    @Test
    fun `an uploaded object downloads byte for byte`() {
        runBlocking { lfs.upload(lfsBase, user, pointer, audio) }
        val downloaded = File(root, "downloaded.wav")
        runBlocking { lfs.download(lfsBase, user, pointer, downloaded) }

        assertContentEquals(audio.readBytes(), downloaded.readBytes())
        assertTrue(wacs.requests.any { it.method == "POST" && it.path.endsWith("/info/lfs/verify") }, "the upload was verified")
    }

    @Test
    fun `an object the host already has isn't uploaded again`() {
        runBlocking { lfs.upload(lfsBase, user, pointer, audio) }
        runBlocking { lfs.upload(lfsBase, user, pointer, audio) }

        assertEquals(1, transfers("PUT").size)
    }

    @Test
    fun `a download that doesn't hash to its oid fails and leaves no file`() {
        runBlocking { lfs.upload(lfsBase, user, pointer, audio) }
        wacs.corruptLfsObject(repo.fullName, pointer.oid)
        val downloaded = File(root, "downloaded.wav")

        val failure = assertFailsWith<Exception> { runBlocking { lfs.download(lfsBase, user, pointer, downloaded) } }

        assertContains(failure.message.orEmpty(), "integrity")
        assertFalse(downloaded.exists())
    }

    @Test
    fun `a download of an object the host doesn't have is refused`() {
        val failure = assertFailsWith<Exception> { runBlocking { lfs.download(lfsBase, user, pointer, File(root, "missing.wav")) } }

        assertContains(failure.message.orEmpty(), "refused")
    }

    @Test
    fun `the host rejects a wrong password`() {
        val failure = assertFailsWith<Exception> {
            runBlocking { lfs.upload(lfsBase, WacsCredential.Basic("translator", "wrong"), pointer, audio) }
        }

        assertContains(failure.message.orEmpty(), "HTTP 401")
        assertNull(wacs.lfsObject(repo.fullName, pointer.oid))
    }

    @Test
    fun `transfers to a separate object store carry its own header, not the WACS credentials`() {
        wacs.objectStoreHost = "objects.test"

        runBlocking { lfs.upload(lfsBase, user, pointer, audio) }
        runBlocking { lfs.download(lfsBase, user, pointer, File(root, "downloaded.wav")) }

        val toStore = wacs.requests.filter { it.host == "objects.test" }
        assertEquals(listOf("PUT", "GET"), toStore.map { it.method })
        assertTrue(toStore.all { it.headers[FakeWacs.PRESIGNED_HEADER] == "signed" && it.headers["Authorization"] == null })
        val batches = wacs.requests.filter { it.path.endsWith("/objects/batch") }
        assertTrue(batches.all { it.headers["Authorization"] == user.authorizationHeader() }, "the batch API is still signed in")
    }
}
