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
package org.bibletranslationtools.otter.common.domain.wacs.usecase

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoApi
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.git.JGitWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.bibletranslationtools.otter.common.domain.wacs.lfs.Sha256
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in, end-to-end M3 test: runs the REAL [ListWacsRepos] -> [CloneWacsRepo] -> [PullChapter]
 * chain against the live WACS prototype's real seeded official repo `AudioTranslation/audio-en-ulb-mrk`
 * (see the plan doc, §8, M3, and the M0/M2 pattern this mirrors:
 * [org.bibletranslationtools.otter.common.domain.wacs.WacsSyncDesktopTest] /
 * [PublishChapterToWacsDesktopTest]).
 *
 * Skipped unless `WACS_USER`/`WACS_PASS` are set (keeps `check`/CI green with no prototype running).
 * Run: `WACS_USER=user1 WACS_PASS=user1234 ./gradlew :shared:desktopTest --tests "*PullChapterDesktopTest*"`.
 *
 * Pure pull — no server-side writes, so nothing to delete on the prototype afterward (unlike the
 * publish test, which forks + opens a PR that it must clean up). Only local temp/cache dirs are
 * created, cleaned up in a `finally` block.
 */
class PullChapterDesktopTest {

    private val http = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(ForgejoApi.WA_TOOL_HEADER, ForgejoApi.WA_TOOL_VALUE)
                    .build()
            )
        }
        .build()

    @Test
    fun `list repos, clone, read scope, and pull a chapter with verified integrity`() {
        val user = System.getenv("WACS_USER")
        val pass = System.getenv("WACS_PASS")
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return // skip when no creds
        val baseUrl = (System.getenv("WACS_BASE_URL") ?: "http://localhost:3000").trimEnd('/')
        val cred = WacsCredential.Basic(user, pass)

        val cacheRoot = File(System.getProperty("java.io.tmpdir"), "wacs-pull-test-${UUID.randomUUID()}")
            .apply { mkdirs() }

        val session = WacsSession().apply { set(cred, baseUrl) }
        val config = WacsConfig(baseUrl = baseUrl, org = "AudioTranslation")
        val apiFactory = WacsApiFactory(http)
        val gitClient = JGitWacsGitClient()
        val lfsClient = LfsBatchClient(http)
        val appDirectories = object : IAppDirectories {
            override fun getUserDataDirectory(appendedPath: String): File = File(cacheRoot, "user/$appendedPath")
            override fun getAppDataDirectory(appendedPath: String): File = File(cacheRoot, "app/$appendedPath")
            override val databaseDirectory: File = File(cacheRoot, "db")
            override val versificationDirectory: File = File(cacheRoot, "versification")
            override val audioPluginDirectory: File = File(cacheRoot, "plugins")
            override val userProfileImageDirectory: File = File(cacheRoot, "profile-image")
            override val userProfileAudioDirectory: File = File(cacheRoot, "profile-audio")
            override val logsDirectory: File = File(cacheRoot, "logs")
            override val cacheDirectory: File = File(cacheRoot, "cache").apply { mkdirs() }
        }

        val listRepos = ListWacsRepos(apiFactory, session, config)
        val cloneRepo = CloneWacsRepo(apiFactory, gitClient, session, config, appDirectories)
        val pullChapter = PullChapter(gitClient, lfsClient, session, appDirectories)

        runBlocking {
            try {
                // ── 1. List repos: the real official test repo must be in the org listing ──────
                val repos = listRepos.list()
                assertTrue(
                    repos.any { it.name == "audio-en-ulb-mrk" },
                    "expected audio-en-ulb-mrk in AudioTranslation's repo listing, got: ${repos.map { it.name }}"
                )

                // ── 2. Clone (pointers only) + read scope — no audio bytes yet ──────────────────
                val opened = cloneRepo.open("audio-en-ulb-mrk")
                assertEquals("audio-en-ulb-mrk", opened.repo.name)
                assertTrue(File(opened.workDir, ".git").isDirectory, "expected a real git working tree")

                val mrkChapters = opened.scope.books["MRK"]
                assertTrue(!mrkChapters.isNullOrEmpty(), "expected at least one MRK chapter in scope, got: ${opened.scope.books}")
                val chapter1 = mrkChapters.find { it.chapterNumber == 1 }
                assertTrue(chapter1 != null, "expected chapter 1 ingredient, got: $mrkChapters")

                // The checked-out audio ingredient must be a pointer, not real bytes (clone never
                // smudges LFS content — see GitConfigIsolation).
                val checkedOutAudio = File(opened.workDir, chapter1.audioPath)
                assertTrue(checkedOutAudio.exists(), "expected ${chapter1.audioPath} checked out in the clone")
                val pointerBytes = checkedOutAudio.readBytes()
                assertTrue(
                    LfsPointer.isPointer(pointerBytes),
                    "expected ${chapter1.audioPath} to be an LFS pointer after clone, not real audio"
                )

                // ── 3. Pull chapter 1: LFS-download + verify + materialize ──────────────────────
                val pulled = pullChapter.pull(opened.repo, opened.workDir, chapter1)
                assertTrue(pulled.audioFile.exists() && pulled.audioFile.length() > 0)

                val expectedOid = Sha256.hex(pulled.audioFile)
                val pointer = LfsPointer.parse(pointerBytes)
                assertTrue(pointer != null, "expected a parseable pointer in $checkedOutAudio")
                assertEquals(
                    pointer.oid,
                    expectedOid,
                    "materialized audio's sha256 must match the pointer's oid"
                )
                assertEquals(pointer.size, pulled.audioFile.length())

                // Companion (timing/alignment) ingredient, if any, materialized too.
                if (chapter1.companionPaths.isNotEmpty()) {
                    assertTrue(pulled.companionFiles.isNotEmpty(), "expected companion file(s) to be materialized")
                }
            } finally {
                runCatching { cacheRoot.deleteRecursively() }
            }
        }
    }
}
