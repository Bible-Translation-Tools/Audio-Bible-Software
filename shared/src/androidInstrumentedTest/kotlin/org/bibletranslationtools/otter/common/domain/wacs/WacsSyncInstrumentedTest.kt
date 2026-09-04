/*
 * Copyright (C) 2020-2026 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.domain.wacs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.git.JGitWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * The capstone M0 test: runtime-exercise the REAL Kotlin WACS client — [JGitWacsGitClient] +
 * [LfsBatchClient] — on an Android device against the live Forgejo prototype. Proves JGit
 * clone/commit/push and the hand-rolled LFS upload/download round-trip actually run on ART, that
 * host git config is irrelevant (there is none on-device), and that a checked-out LFS path is a
 * POINTER, not audio.
 *
 * REQUIRES API 26+ (Android 8): JGit's object database relies on java.nio.file operations that are
 * native from API 26 but only partially covered by library desugaring on API 24/25, where these
 * operations fail (MissingObjectException). WACS sync is gated to API 26+ accordingly. Verified
 * green on API 33; the same run on API 24 fails at commit (that is the gate's justification).
 *
 * Skipped unless a credential is supplied (keeps CI green with no prototype). Run with, e.g.:
 * ```
 * ./gradlew :shared:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.wacsUser=user1 \
 *   -Pandroid.testInstrumentationRunnerArguments.wacsPass=user1234 \
 *   -Pandroid.testInstrumentationRunnerArguments.wacsBaseUrl=http://10.0.2.2:3000
 * ```
 */
@RunWith(AndroidJUnit4::class)
class WacsSyncInstrumentedTest {

    private val args = InstrumentationRegistry.getArguments()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val http = OkHttpClient()
    private val jsonMedia = MediaType.parse("application/json")
    private val lfsMedia = MediaType.parse("application/vnd.git-lfs+json")

    @Test
    fun clone_commit_upload_push_and_roundtrip() {
        val user = args.getString("wacsUser")
        val pass = args.getString("wacsPass")
        assumeTrue("needs -P…wacsUser/wacsPass", !user.isNullOrBlank() && !pass.isNullOrBlank())
        val baseUrl = (args.getString("wacsBaseUrl") ?: "http://10.0.2.2:3000").trimEnd('/')
        val cred = WacsCredential.Basic(user!!, pass!!)

        val git = JGitWacsGitClient()      // installs GitConfigIsolation
        val lfs = LfsBatchClient(http)
        val repo = "andtest-" + System.currentTimeMillis()
        val cloneUrl = "$baseUrl/$user/$repo.git"
        val lfsBase = "$cloneUrl/info/lfs"

        runBlocking {
            createRepo(baseUrl, cred, repo)
            try {
                // 1) clone (pointers only) → 2) stage LFS-tracked wav as a pointer
                val dir = File(ctx.cacheDir, repo).also { it.deleteRecursively() }
                git.clone(cloneUrl, dir, cred)

                File(dir, ".gitattributes").writeText("*.wav filter=lfs diff=lfs merge=lfs -text\n")
                val wav = tinyWav()
                val oid = sha256Hex(wav)
                val pointer = LfsPointer(oid, wav.size.toLong())
                val staging = File(ctx.cacheDir, "$repo-src.wav").also { it.writeBytes(wav) }
                File(dir, "ingredients/MRK").mkdirs()
                File(dir, "ingredients/MRK/01.wav").writeText(pointer.serialize())

                // 3) LFS upload the real bytes → 4) commit pointer → 5) push
                lfs.upload(lfsBase, cred, pointer, staging)
                git.commit(dir, listOf(".gitattributes", "ingredients/MRK/01.wav"),
                    "MRK 1: official take (android probe)", "WACS Probe", "probe@example.org")
                git.push(dir, cred)

                // 6) verify from a FRESH clone: pointer committed (not audio), and it re-downloads
                val verifyDir = File(ctx.cacheDir, "$repo-verify").also { it.deleteRecursively() }
                git.clone(cloneUrl, verifyDir, cred)

                val tracked = git.listLfsPointers(verifyDir)
                assertTrue("LFS pointer must be committed at the wav path",
                    tracked.any { it.path == "ingredients/MRK/01.wav" && it.pointer.oid == oid })
                val checkedOut = File(verifyDir, "ingredients/MRK/01.wav").readBytes()
                assertTrue("checked-out wav must be a pointer, not audio", LfsPointer.isPointer(checkedOut))

                val downloaded = File(ctx.cacheDir, "$repo-dl.wav")
                lfs.download(lfsBase, cred, pointer, downloaded)
                assertArrayEquals("round-tripped bytes must match", wav, downloaded.readBytes())
            } finally {
                runCatching { deleteRepo(baseUrl, cred, user, repo) }
            }
        }
    }

    // ── raw Forgejo REST for setup/teardown (avoids the converter-wiring TODO) ──
    private fun createRepo(baseUrl: String, cred: WacsCredential, name: String) {
        val body = """{"name":"$name","private":false,"auto_init":true,"default_branch":"master"}"""
        val req = Request.Builder()
            .url("$baseUrl/api/v1/user/repos")
            .header("Authorization", cred.authorizationHeader())
            .header("Accept", "application/json")
            .post(RequestBody.create(jsonMedia, body))
            .build()
        http.newCall(req).execute().use { check(it.isSuccessful) { "create repo HTTP ${it.code()}" } }
    }

    private fun deleteRepo(baseUrl: String, cred: WacsCredential, owner: String, name: String) {
        val req = Request.Builder()
            .url("$baseUrl/api/v1/repos/$owner/$name")
            .header("Authorization", cred.authorizationHeader())
            .delete()
            .build()
        http.newCall(req).execute().close()
    }

    private fun tinyWav(): ByteArray {
        val samples = 800; val dataLen = samples * 2; val total = 36 + dataLen
        val b = ByteArrayOutputStream()
        fun le16(v: Int) { b.write(v and 0xff); b.write((v shr 8) and 0xff) }
        fun le32(v: Int) { b.write(v and 0xff); b.write((v shr 8) and 0xff); b.write((v shr 16) and 0xff); b.write((v shr 24) and 0xff) }
        b.write("RIFF".toByteArray()); le32(total); b.write("WAVE".toByteArray())
        b.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(16000); le32(32000); le16(2); le16(16)
        b.write("data".toByteArray()); le32(dataLen)
        for (i in 0 until samples) le16(((Math.sin(i * 0.1) * 3000).toInt()) and 0xffff)
        return b.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
