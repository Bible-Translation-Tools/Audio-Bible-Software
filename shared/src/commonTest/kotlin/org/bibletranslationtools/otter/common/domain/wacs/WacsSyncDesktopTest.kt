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

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.git.JGitWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

/**
 * Desktop counterpart of the Android instrumented WACS test — runs the SAME client classes on the
 * JVM against a local prototype. Skipped unless WACS_USER/WACS_PASS are set (keeps `check` green).
 * Run: `WACS_USER=user1 WACS_PASS=user1234 ./gradlew :shared:desktopTest --tests "*WacsSyncDesktopTest*"`.
 */
class WacsSyncDesktopTest {

    private val http = OkHttpClient()
    private val jsonMedia = MediaType.parse("application/json")

    @Test
    fun clone_commit_upload_push_and_roundtrip() {
        val user = System.getenv("WACS_USER")
        val pass = System.getenv("WACS_PASS")
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return // skip when no creds
        val baseUrl = (System.getenv("WACS_BASE_URL") ?: "http://localhost:3000").trimEnd('/')
        val cred = WacsCredential.Basic(user, pass)

        val git = JGitWacsGitClient()
        val lfs = LfsBatchClient(http)
        val repo = "desktoptest-" + System.currentTimeMillis()
        val cloneUrl = "$baseUrl/$user/$repo.git"
        val lfsBase = "$cloneUrl/info/lfs"
        val tmp = File(System.getProperty("java.io.tmpdir"), repo)

        runBlocking {
            createRepo(baseUrl, cred, repo)
            try {
                val dir = File(tmp, "clone").also { it.deleteRecursively(); it.mkdirs() }
                git.clone(cloneUrl, dir, cred)

                File(dir, ".gitattributes").writeText("*.wav filter=lfs diff=lfs merge=lfs -text\n")
                val wav = ByteArray(1200) { (it % 251).toByte() }
                val oid = sha256Hex(wav)
                val pointer = LfsPointer(oid, wav.size.toLong())
                val staging = File(tmp, "src.wav").also { it.writeBytes(wav) }
                File(dir, "ingredients/MRK").mkdirs()
                File(dir, "ingredients/MRK/01.wav").writeText(pointer.serialize())

                lfs.upload(lfsBase, cred, pointer, staging)
                git.commit(dir, listOf(".gitattributes", "ingredients/MRK/01.wav"), "MRK 1", "T", "t@e")
                git.push(dir, cred)

                val verifyDir = File(tmp, "verify").also { it.deleteRecursively(); it.mkdirs() }
                git.clone(cloneUrl, verifyDir, cred)
                val tracked = git.listLfsPointers(verifyDir)
                assertTrue(tracked.any { it.path == "ingredients/MRK/01.wav" && it.pointer.oid == oid })
                assertTrue(LfsPointer.isPointer(File(verifyDir, "ingredients/MRK/01.wav").readBytes()))
                val dl = File(tmp, "dl.wav")
                lfs.download(lfsBase, cred, pointer, dl)
                assertContentEquals(wav, dl.readBytes())
            } finally {
                runCatching { deleteRepo(baseUrl, cred, user, repo) }
                tmp.deleteRecursively()
            }
        }
    }

    private fun createRepo(baseUrl: String, cred: WacsCredential, name: String) {
        val body = """{"name":"$name","private":false,"auto_init":true,"default_branch":"master"}"""
        val req = Request.Builder().url("$baseUrl/api/v1/user/repos")
            .header("Authorization", cred.authorizationHeader()).header("Accept", "application/json")
            .post(RequestBody.create(jsonMedia, body)).build()
        http.newCall(req).execute().use { check(it.isSuccessful) { "create repo HTTP ${it.code()}" } }
    }

    private fun deleteRepo(baseUrl: String, cred: WacsCredential, owner: String, name: String) {
        val req = Request.Builder().url("$baseUrl/api/v1/repos/$owner/$name")
            .header("Authorization", cred.authorizationHeader()).delete().build()
        http.newCall(req).execute().close()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
