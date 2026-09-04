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

import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.ReplayRelay
import io.mockk.mockk
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.audio.BookMarker
import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
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
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoApi
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.git.JGitWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.bibletranslationtools.otter.common.domain.wacs.lfs.Sha256
import org.bibletranslationtools.scriptureburrito.BURRITO_JSON
import org.bibletranslationtools.scriptureburrito.MetadataSchema
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in, end-to-end M2/M4 test: runs the REAL [PublishChapterToWacs] — fork -> clone -> sync from
 * upstream -> commit -> LFS upload -> push -> open/reuse pull request -> verify — against the live
 * WACS prototype, using a synthesized minimal workbook/chapters/takes rather than a full recorded
 * project (see the plan doc, §9's "Definition of done", and the M0 pattern this mirrors:
 * [org.bibletranslationtools.otter.common.domain.wacs.WacsSyncDesktopTest]).
 *
 * Skipped unless `WACS_USER`/`WACS_PASS` are set (keeps `check`/CI green with no prototype running).
 * Run: `WACS_USER=user1 WACS_PASS=user1234 ./gradlew :shared:desktopTest --tests "*PublishChapterToWacsDesktopTest*"`.
 *
 * Targets the real official test repo `AudioTranslation/audio-en-ulb-mrk` (fixed by the fixture's
 * language/edition/book so [WacsRepoLayout.repoName] produces exactly that name) and forks it into
 * `WACS_USER`'s own account; the fork AND any pull request opened against the official repo are
 * cleaned up in a `finally` block regardless of outcome (see [deleteRepo]/[closePullRequest]).
 *
 * M4: publishes TWO chapters (so the fixture's fork/branch genuinely gets a second push) and
 * asserts that [PublishChapterToWacs.openContributionPullRequest] opens exactly one PR after the
 * first chapter and REUSES that same PR (rather than creating a second one, or erroring) after the
 * second — see that method's KDoc for why one-per-session is correct.
 *
 * NOTE for reviewers: this live prototype's *official* repo's own `metadata.json` does not parse
 * against this codebase's vendored `kotlin-scripture-burrito` model (its `meta` object has no
 * `category` field, and it uses `type_specific`/`.timing` rather than this model's `type`/alignment
 * JSON) — so `MetadataJsonWriter` here falls back to building a fresh manifest rather than merging
 * into the real one. That fallback path is exactly what's exercised below; it is NOT a bug in this
 * test. See the PublishChapterToWacs report for follow-up. It also means the fork's `master` is
 * NEVER behind the official repo's `master` in this synthesized fixture, so
 * [org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient.syncFromUpstream] only
 * ever exercises its no-op (`ALREADY_UP_TO_DATE`) path here; its fast-forward path is exercised by
 * unit coverage elsewhere / manual verification (see the M4 report) since reproducing "someone else
 * merged upstream" needs a second account's contribution actually merged into the official repo.
 */
class PublishChapterToWacsDesktopTest {

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
    fun `publish forks, commits, uploads, pushes, and round-trips a chapter`() {
        val user = System.getenv("WACS_USER")
        val pass = System.getenv("WACS_PASS")
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return // skip when no creds
        val baseUrl = (System.getenv("WACS_BASE_URL") ?: "http://localhost:3000").trimEnd('/')
        val cred = WacsCredential.Basic(user, pass)

        val tmp = File(System.getProperty("java.io.tmpdir"), "wacs-publish-test-${UUID.randomUUID()}")
            .apply { mkdirs() }

        val session = WacsSession().apply { set(cred, baseUrl) }
        val config = WacsConfig(baseUrl = baseUrl, org = "AudioTranslation")
        val apiFactory = WacsApiFactory(http)
        val gitClient = JGitWacsGitClient()
        val lfsClient = LfsBatchClient(http)
        val tempFileProvider = object : ITempFileProvider {
            override val tempDirectory: File = tmp
            override fun createTempFile(prefix: String, suffix: String?): File =
                File.createTempFile(prefix, suffix, tmp)
            override fun cleanTempDirectory() {
                tmp.listFiles()?.forEach { it.deleteRecursively() }
            }
        }
        val useCase = PublishChapterToWacs(apiFactory, gitClient, lfsClient, session, config, tempFileProvider)

        val wavFile1 = File(tmp, "mrk-01-take1.wav")
        val wavFile2 = File(tmp, "mrk-02-take1.wav")
        val workbook = buildWorkbook(wavFile1, wavFile2)
        val repoName = WacsRepoLayout.repoName(workbook)
        assertEquals("audio-en-ulb-mrk", repoName, "fixture must target the real official test repo")

        var pullRequestNumber: Long? = null
        runBlocking {
            try {
                val result = useCase.publish(PublishChapterToWacs.Request(workbook, chapterNumber = 1)) { }

                assertEquals(1, result.chapterNumber)
                assertTrue(result.commitSha.isNotBlank())
                assertEquals("$user/$repoName", result.forkFullName)

                // Verify server-side from a FRESH clone: an LFS pointer (not audio) is committed,
                // and the real bytes round-trip byte-identical via the LFS Batch API.
                val cloneUrl = "$baseUrl/$user/$repoName.git"
                val verifyDir = File(tmp, "verify").apply { mkdirs() }
                gitClient.clone(cloneUrl, verifyDir, cred)

                val bookSlug = WacsRepoLayout.bookSlug(workbook)
                val audioPath = WacsRepoLayout.audioIngredientPath(bookSlug, 1, "wav")
                val alignmentPath = WacsRepoLayout.alignmentIngredientPath(bookSlug, 1)

                val tracked = gitClient.listLfsPointers(verifyDir)
                val trackedAudio = tracked.find { it.path == audioPath }
                assertTrue(trackedAudio != null, "expected an LFS pointer at $audioPath, got: $tracked")
                val expectedOid = Sha256.hex(wavFile1)
                assertEquals(expectedOid, trackedAudio!!.pointer.oid)
                assertEquals(wavFile1.length(), trackedAudio.pointer.size)

                val checkedOutBytes = File(verifyDir, audioPath).readBytes()
                assertTrue(
                    LfsPointer.isPointer(checkedOutBytes),
                    "checked-out $audioPath must be a pointer, not audio bytes"
                )

                val downloaded = File(tmp, "downloaded.wav")
                lfsClient.download(
                    "${cloneUrl.trimEnd('/')}/info/lfs",
                    cred,
                    LfsPointer(expectedOid, wavFile1.length()),
                    downloaded
                )
                assertContentEquals(wavFile1.readBytes(), downloaded.readBytes())

                // The alignment JSON is real (non-LFS) content, committed as-is.
                val alignmentFile = File(verifyDir, alignmentPath)
                assertTrue(alignmentFile.exists() && alignmentFile.length() > 0)

                // metadata.json must at least parse with our own model and list both new
                // ingredients — see the class doc for why this exercises the from-scratch
                // fallback rather than a merge into the official repo's own manifest.
                val metadata: MetadataSchema = BURRITO_JSON.decodeFromString(
                    MetadataSchema.serializer(),
                    File(verifyDir, WacsRepoLayout.METADATA_PATH).readText()
                )
                assertTrue(metadata.ingredients.containsKey(audioPath))
                assertTrue(metadata.ingredients.containsKey(alignmentPath))

                // ── M4: open the session's PR after chapter 1 ────────────────────────────────
                val firstPr = useCase.openContributionPullRequest(result)
                assertTrue(firstPr.number > 0)
                assertTrue(firstPr.htmlUrl.isNotBlank())
                assertTrue(
                    !firstPr.reused,
                    "no PR should have existed yet for a freshly-created fork/branch"
                )
                pullRequestNumber = firstPr.number

                // Verify via the API (not just trusting our own return value): the official repo
                // must show exactly one open PR whose head is this fork's branch.
                val apiForVerification = apiFactory.create(baseUrl)
                val openAfterFirst = apiForVerification.listPullRequests(
                    config.org, repoName, cred.authorizationHeader(), state = "open"
                )
                assertEquals(
                    1,
                    openAfterFirst.count { it.number == firstPr.number },
                    "expected exactly one open PR #${firstPr.number} against $repoName, got: $openAfterFirst"
                )

                // ── M4: publish a second, genuinely different chapter to the SAME fork/branch,
                // then confirm the SAME PR is reused rather than a duplicate being created/errored.
                val result2 = useCase.publish(PublishChapterToWacs.Request(workbook, chapterNumber = 2)) { }
                assertEquals(2, result2.chapterNumber)

                val secondPr = useCase.openContributionPullRequest(result2)
                assertEquals(firstPr.number, secondPr.number, "a second chapter must reuse the same PR")
                assertEquals(firstPr.htmlUrl, secondPr.htmlUrl)
                assertTrue(secondPr.reused, "the already-open PR must be reused, not re-created")

                val openAfterSecond = apiForVerification.listPullRequests(
                    config.org, repoName, cred.authorizationHeader(), state = "open"
                )
                assertEquals(
                    1,
                    openAfterSecond.size,
                    "publishing a second chapter must not open a second PR: $openAfterSecond"
                )
            } finally {
                runCatching { pullRequestNumber?.let { closePullRequest(baseUrl, cred, config.org, repoName, it) } }
                runCatching { deleteRepo(baseUrl, cred, user, repoName) }
                runCatching { tmp.deleteRecursively() }
            }
        }
    }

    /** `PATCH /repos/{owner}/{repo}/pulls/{index}` with `{"state":"closed"}` — test cleanup only. */
    private fun closePullRequest(baseUrl: String, cred: WacsCredential, owner: String, repo: String, number: Long) {
        val req = Request.Builder()
            .url("$baseUrl/api/v1/repos/$owner/$repo/pulls/$number")
            .header("Authorization", cred.authorizationHeader())
            .patch(RequestBody.create(MediaType.parse("application/json"), """{"state":"closed"}"""))
            .build()
        http.newCall(req).execute().close()
    }

    /**
     * A synthesized minimal workbook: one book ("mrk", language "en", edition "ulb" — chosen so
     * [WacsRepoLayout.repoName] matches the real official test repo), TWO chapters (sort 1 and 2 —
     * M4 needs a second, genuinely different push to the same fork/branch to exercise "reuse the
     * existing PR"), each with a selected take that is a real, tiny WAV on disk carrying book+chapter
     * markers (so the alignment JSON is produced via the normal marker path, not the empty-markers
     * fallback).
     */
    private fun buildWorkbook(wavFile1: File, wavFile2: File): Workbook {
        val chapter1 = buildChapter(1, wavFile1)
        val chapter2 = buildChapter(2, wavFile2)

        val language = Language(
            slug = "en",
            name = "English",
            anglicizedName = "English",
            direction = "ltr",
            isGateway = true,
            region = ""
        )
        val resourceMetadata = ResourceMetadata(
            conformsTo = "0.2",
            creator = "PublishChapterToWacsDesktopTest",
            description = "",
            format = "text/usfm",
            identifier = "ulb",
            issued = LocalDate.now(),
            language = language,
            modified = LocalDate.now(),
            publisher = "test",
            subject = "Bible",
            type = ContainerType.Book,
            title = "Unlocked Literal Bible",
            version = "1",
            license = "CC BY-SA 4.0",
            path = File(".")
        )

        val book = Book(
            collectionId = 1,
            sort = 1,
            slug = "mrk",
            title = "Mark",
            label = "Mark",
            chapters = Observable.fromIterable(listOf(chapter1, chapter2)),
            resourceMetadata = resourceMetadata,
            linkedResources = emptyList(),
            modifiedTs = null,
            subtreeResources = emptyList()
        )

        return Workbook(
            directoryProvider = mockk<IDirectoryProvider>(relaxed = true),
            source = book,
            target = book,
            translation = AssociatedTranslation(
                BehaviorRelay.createDefault(1.0),
                BehaviorRelay.createDefault(1.0)
            )
        )
    }

    private fun buildChapter(chapterNumber: Int, wavFile: File): Chapter {
        val oratureAudio = OratureAudioFile(wavFile, 1, 44100, 16)
        oratureAudio.addMarker(BookMarker("mrk", 0))
        oratureAudio.addMarker(ChapterMarker(chapterNumber, 0))
        oratureAudio.update()

        val take = Take(
            name = wavFile.name,
            file = wavFile,
            number = 1,
            format = MimeType.WAV,
            createdTimestamp = LocalDate.now()
        )
        val audio = AssociatedAudio(ReplayRelay.create())
        audio.insertTake(take)
        audio.selectTake(take)

        return Chapter(
            sort = chapterNumber,
            title = "Chapter $chapterNumber",
            label = chapterNumber.toString(),
            audio = audio,
            resources = emptyList(),
            subtreeResources = emptyList(),
            lazychunks = lazy { BehaviorRelay.createDefault<List<Chunk>>(emptyList()) },
            chunkCount = Single.just(0),
            addChunk = { _: List<Content> -> Completable.complete() },
            reset = { Completable.complete() }
        )
    }

    private fun deleteRepo(baseUrl: String, cred: WacsCredential, owner: String, name: String) {
        val req = Request.Builder()
            .url("$baseUrl/api/v1/repos/$owner/$name")
            .header("Authorization", cred.authorizationHeader())
            .delete()
            .build()
        http.newCall(req).execute().close()
    }
}
