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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.audio.metadata.BurritoAlignmentMetadata
import org.bibletranslationtools.kotlinscripturealignment.model.BurritoAudioAlignment
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.api.CreatePullRequestRequest
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoApi
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoPullRequest
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.layout.MetadataJsonWriter
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.bibletranslationtools.otter.common.domain.wacs.lfs.Sha256
import org.bibletranslationtools.scriptureburrito.BURRITO_JSON
import org.bibletranslationtools.scriptureburrito.MetadataSchema
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * M2/M4: publish one recorded chapter to the user's fork of `AudioTranslation/<repo>`, and (M4)
 * complete the contribution loop back to the official repo.
 *
 * Orchestrates, in the order the plan's §0 requires (pointer written and committed BEFORE the real
 * bytes are uploaded, and uploaded BEFORE the ref is pushed):
 *
 * 1. Resolve (or fork) the user's copy of the official repo via [ForgejoApi].
 * 2. Clone the fork (pointers only — [IWacsGitClient] runs with host git config isolated).
 * 3. M4: fast-forward the fork's default branch to the official repo's, so a fork left behind by
 *    someone else's merged contribution doesn't turn this session's push into a rejected
 *    non-fast-forward (see [IWacsGitClient.syncFromUpstream]).
 * 4. Produce the chapter's alignment JSON from the selected take's master WAV, reusing the same
 *    `BurritoAlignmentMetadata`/`OratureAudioFile` machinery `BurritoWrapperExporter` uses — no new
 *    serializer.
 * 5. Write the LFS *pointer* text (never the audio bytes) + refreshed `.gitattributes` +
 *    alignment JSON + `metadata.json` into the working tree, via [WacsRepoLayout]/[MetadataJsonWriter].
 * 6. `git add` + commit (pointer + text only).
 * 7. LFS Batch-upload the real bytes.
 * 8. Push to the fork.
 *
 * M4's other half, [openContributionPullRequest], is a separate call the UI makes ONCE per publish
 * session — after the loop over every selected chapter has finished calling [publish] — not once
 * per chapter; see that method's KDoc for why.
 *
 * Every failure is classified into a [WacsPublishException] — no JGit, retrofit2, or java.io
 * exception crosses out of this class, mirroring `BasicAuthenticator`'s error-boundary pattern.
 */
class PublishChapterToWacs(
    private val apiFactory: WacsApiFactory,
    private val gitClient: IWacsGitClient,
    private val lfsClient: LfsBatchClient,
    private val session: WacsSession,
    private val config: WacsConfig,
    private val tempFileProvider: ITempFileProvider,
) {
    data class Request(val workbook: Workbook, val chapterNumber: Int)

    data class Result(
        val forkFullName: String,
        val forkHtmlUrl: String?,
        val chapterNumber: Int,
        val commitSha: String,
        /** M4: enough of the fork/official-repo identity to open the session's PR afterward. */
        val forkId: Long,
        val forkOwner: String,
        val branch: String,
        val officialDefaultBranch: String,
    )

    /** The pull request [openContributionPullRequest] found or opened. */
    data class PullRequestResult(
        val number: Long,
        val htmlUrl: String,
        /** True if an open PR for this fork/branch already existed and was reused, not created. */
        val reused: Boolean,
    )

    /** Coarse publish stages, reported via [onProgress] for a progress UI (fork -> ... -> push). */
    sealed interface Progress {
        data object CheckingOfficialRepo : Progress
        data object Forking : Progress
        data object Cloning : Progress
        data object SyncingUpstream : Progress
        data object PreparingFiles : Progress
        data object Committing : Progress
        data class UploadingAudio(val totalBytes: Long) : Progress
        data object Pushing : Progress
        data object OpeningPullRequest : Progress
        data object Done : Progress
    }

    suspend fun publish(request: Request, onProgress: (Progress) -> Unit = {}): Result {
        val credential = session.credential
            ?: throw WacsPublishException(WacsPublishException.Reason.NOT_AUTHENTICATED)
        val username = (credential as? WacsCredential.Basic)?.username
            ?: throw WacsPublishException(WacsPublishException.Reason.NOT_AUTHENTICATED)
        val host = session.host ?: config.baseUrl
        val api = apiFactory.create(host)
        val auth = credential.authorizationHeader()

        val repoName = WacsRepoLayout.repoName(request.workbook)

        onProgress(Progress.CheckingOfficialRepo)
        val officialRepo = ensureOfficialRepoExists(api, repoName, auth)

        onProgress(Progress.Forking)
        val fork = forkOrGetExisting(api, repoName, auth, username)

        val chapter = try {
            request.workbook.target.chapters.toList().await()
                .find { it.sort == request.chapterNumber }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPublishException(WacsPublishException.Reason.UNKNOWN, e)
        } ?: throw WacsPublishException(WacsPublishException.Reason.UNKNOWN, IllegalArgumentException("No chapter ${request.chapterNumber}"))

        val take = chapter.getSelectedTake()?.takeIf { it.file.exists() }
            ?: throw WacsPublishException(WacsPublishException.Reason.NO_AUDIO)

        var workDir: File? = null
        var stagingDir: File? = null
        try {
            workDir = File(tempFileProvider.tempDirectory, "wacs-publish-${UUID.randomUUID()}")

            onProgress(Progress.Cloning)
            try {
                gitClient.clone(fork.cloneUrl, workDir, credential, fork.defaultBranch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classifyGitFailure(e)
            }

            // M4: catch the fork up to the official repo BEFORE writing/committing anything, so a
            // fork left behind by someone else's merged contribution can't turn this session's
            // push into a rejected non-fast-forward. Pointer-only, and a no-op on a fresh fork.
            onProgress(Progress.SyncingUpstream)
            try {
                gitClient.syncFromUpstream(workDir, officialRepo.cloneUrl, credential, officialRepo.defaultBranch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classifyGitFailure(e)
            }

            onProgress(Progress.PreparingFiles)
            val bookSlug = WacsRepoLayout.bookSlug(request.workbook)
            val extension = take.file.extension.lowercase().ifBlank { "wav" }
            val audioPath = WacsRepoLayout.audioIngredientPath(bookSlug, request.chapterNumber, extension)
            val alignmentPath = WacsRepoLayout.alignmentIngredientPath(bookSlug, request.chapterNumber)
            val metadataFile = File(workDir, WacsRepoLayout.METADATA_PATH)

            // Every step below is local file I/O + in-memory model work (no network) — any failure
            // is UNKNOWN, there being no more specific WACS-domain reason for a local disk error.
            // The block's value is the LFS pointer, needed by the upload step further down.
            val pointer: LfsPointer = try {
                // Alignment JSON is produced in a staging dir OUTSIDE the git worktree (plan §0,
                // step 1), then copied into the tree as real content (it is not LFS-tracked).
                stagingDir = File(tempFileProvider.tempDirectory, "wacs-stage-${UUID.randomUUID()}").apply { mkdirs() }
                val stagedAlignment = File(stagingDir, "alignment.json")
                val oratureAudio = OratureAudioFile(take.file)
                BurritoAlignmentMetadata(stagedAlignment, take.file)
                    .write(oratureAudio.getMarkers(), request.workbook.target.slug, request.chapterNumber, oratureAudio.totalFrames)
                if (!stagedAlignment.exists()) {
                    // BurritoAlignmentMetadata.write() no-ops when the take carries no markers yet
                    // (shouldn't happen for a finished chapter, but don't hard-fail publish over
                    // it) — fall back to a minimal, valid, empty alignment file for this audio.
                    BurritoAudioAlignment.create(take.file, stagedAlignment)
                }

                val oid = Sha256.hex(take.file)
                val pointer = LfsPointer(oid, take.file.length())

                // §0's #1 correctness pitfall: write the POINTER text at the tracked path, never
                // the audio bytes. The real bytes are read straight from `take.file` by the LFS
                // client below.
                val audioTarget = File(workDir, audioPath).apply { parentFile?.mkdirs() }
                audioTarget.writeText(pointer.serialize())

                val alignmentTarget = File(workDir, alignmentPath).apply { parentFile?.mkdirs() }
                stagedAlignment.copyTo(alignmentTarget, overwrite = true)

                val gitattributes = File(workDir, WacsRepoLayout.GITATTRIBUTES_PATH)
                val attrLine = WacsRepoLayout.gitattributesLine(extension)
                val existingAttrs = if (gitattributes.exists()) gitattributes.readText() else ""
                if (!existingAttrs.contains(attrLine)) {
                    val prefix = existingAttrs.trimEnd('\n').let { if (it.isEmpty()) "" else "$it\n" }
                    gitattributes.writeText("$prefix$attrLine\n")
                }

                val existingMetadata: MetadataSchema? = if (metadataFile.exists() && metadataFile.length() > 0) {
                    runCatching { BURRITO_JSON.decodeFromString(MetadataSchema.serializer(), metadataFile.readText()) }
                        .getOrNull()
                } else null

                val updatedMetadata = MetadataJsonWriter.upsertChapter(
                    existing = existingMetadata,
                    workbook = request.workbook,
                    bookSlug = bookSlug,
                    chapterNumber = request.chapterNumber,
                    audioPath = audioPath,
                    audioFile = take.file,
                    alignmentPath = alignmentPath,
                    alignmentFile = alignmentTarget,
                )
                metadataFile.writeText(BURRITO_JSON.encodeToString(MetadataSchema.serializer(), updatedMetadata))
                pointer
            } catch (e: CancellationException) {
                throw e
            } catch (e: WacsPublishException) {
                throw e
            } catch (e: Exception) {
                throw WacsPublishException(WacsPublishException.Reason.UNKNOWN, e)
            }

            onProgress(Progress.Committing)
            val commitSha = try {
                gitClient.commit(
                    workDir,
                    listOf(WacsRepoLayout.GITATTRIBUTES_PATH, audioPath, alignmentPath, WacsRepoLayout.METADATA_PATH),
                    "Publish $bookSlug ${request.chapterNumber}",
                    username,
                    "$username@users.noreply.wacs",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classifyGitFailure(e)
            }

            onProgress(Progress.UploadingAudio(take.file.length()))
            try {
                lfsClient.upload("${fork.cloneUrl.trimEnd('/')}/info/lfs", credential, pointer, take.file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classifyLfsFailure(e)
            }

            onProgress(Progress.Pushing)
            try {
                gitClient.push(workDir, credential, fork.defaultBranch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classifyGitFailure(e)
            }

            onProgress(Progress.Done)
            return Result(
                forkFullName = fork.fullName,
                forkHtmlUrl = fork.htmlUrl,
                chapterNumber = request.chapterNumber,
                commitSha = commitSha,
                forkId = fork.id,
                forkOwner = username,
                branch = fork.defaultBranch,
                officialDefaultBranch = officialRepo.defaultBranch,
            )
        } finally {
            runCatching { stagingDir?.deleteRecursively() }
            runCatching { workDir?.deleteRecursively() }
        }
    }

    /**
     * M4: ensure a pull request exists from the fork's branch back to
     * `AudioTranslation/<repo>:<default branch>`, so the chapters [publish] already pushed to the
     * fork actually reach the canonical repo as a reviewable contribution.
     *
     * **Call this once per publish session**, after the loop over every selected chapter has
     * finished (any successful [Result] from that loop works — every chapter in a session lands on
     * the exact same fork and branch). Opening or even checking for a PR is a repo-wide,
     * branch-wide fact, not a per-chapter one: all chapters share one branch, so they share one PR.
     * Doing this per chapter instead would mean extra network round-trips for a no-op result after
     * the first chapter (Forgejo/Gitea auto-updates an open PR's diff as more commits land on its
     * head branch, so a later chapter's push already updates the existing PR with zero extra calls
     * here) — once per session is both correct and the cheaper choice.
     *
     * Idempotent: looks for an already-open PR for this exact fork/branch -> official/branch pair
     * first (a previous session's PR, or a race), and reuses it rather than creating a duplicate.
     * If creation still races with something else and Forgejo rejects it as a duplicate (409, or
     * 422 on some Forgejo/Gitea versions), the existing PR is looked up again and surfaced instead
     * of failing the publish.
     */
    suspend fun openContributionPullRequest(after: Result): PullRequestResult {
        val credential = session.credential
            ?: throw WacsPublishException(WacsPublishException.Reason.NOT_AUTHENTICATED)
        val host = session.host ?: config.baseUrl
        val api = apiFactory.create(host)
        val auth = credential.authorizationHeader()
        val repoName = after.forkFullName.substringAfterLast('/')

        try {
            findExistingPullRequest(api, repoName, after, auth)?.let {
                return PullRequestResult(it.number, it.htmlUrl, reused = true)
            }

            val created = try {
                api.createPullRequest(
                    config.org,
                    repoName,
                    auth,
                    CreatePullRequestRequest(
                        head = "${after.forkOwner}:${after.branch}",
                        base = after.officialDefaultBranch,
                        title = "Publish $repoName",
                        body = "Opened automatically by the BTT Recorder WACS publish flow.",
                    ),
                )
            } catch (e: HttpException) {
                if (e.code() == 409 || e.code() == 422) {
                    findExistingPullRequest(api, repoName, after, auth)?.let {
                        return PullRequestResult(it.number, it.htmlUrl, reused = true)
                    }
                }
                throw e
            }
            return PullRequestResult(created.number, created.htmlUrl, reused = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WacsPublishException) {
            throw e
        } catch (e: HttpException) {
            throw classifyHttp(e)
        } catch (e: IOException) {
            throw WacsPublishException(WacsPublishException.Reason.NETWORK, e)
        } catch (e: Exception) {
            throw WacsPublishException(WacsPublishException.Reason.UNKNOWN, e)
        }
    }

    /** An open PR whose head is [after]'s fork/branch and whose base is the official default branch, if any. */
    private suspend fun findExistingPullRequest(
        api: ForgejoApi,
        repoName: String,
        after: Result,
        auth: String,
    ): ForgejoPullRequest? {
        val open = api.listPullRequests(config.org, repoName, auth, state = "open")
        return open.firstOrNull { pr ->
            pr.head?.repoId == after.forkId &&
                pr.head.ref == after.branch &&
                pr.base?.ref == after.officialDefaultBranch
        }
    }

    private suspend fun ensureOfficialRepoExists(api: ForgejoApi, repoName: String, auth: String): ForgejoRepo {
        return try {
            api.getRepo(config.org, repoName, auth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 404) throw WacsPublishException(WacsPublishException.Reason.NO_OFFICIAL_REPO, e)
            throw classifyHttp(e)
        } catch (e: IOException) {
            throw WacsPublishException(WacsPublishException.Reason.NETWORK, e)
        }
    }

    /** Fork the official repo into the caller's account, or fetch the existing fork if already forked (409). */
    private suspend fun forkOrGetExisting(api: ForgejoApi, repoName: String, auth: String, username: String): ForgejoRepo {
        return try {
            api.forkRepo(config.org, repoName, auth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 409) {
                try {
                    api.getRepo(username, repoName, auth)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    throw WacsPublishException(WacsPublishException.Reason.CONFLICT, e2)
                }
            } else {
                throw classifyHttp(e)
            }
        } catch (e: IOException) {
            throw WacsPublishException(WacsPublishException.Reason.NETWORK, e)
        }
    }

    private fun classifyHttp(e: HttpException): WacsPublishException = when {
        e.code() == 401 || e.code() == 403 -> WacsPublishException(WacsPublishException.Reason.AUTH_REJECTED, e)
        e.code() == 409 -> WacsPublishException(WacsPublishException.Reason.CONFLICT, e)
        e.code() >= 500 -> WacsPublishException(WacsPublishException.Reason.NETWORK, e)
        else -> WacsPublishException(WacsPublishException.Reason.UNKNOWN, e)
    }

    private fun classifyGitFailure(e: Exception): WacsPublishException {
        val message = e.message.orEmpty()
        val className = e::class.qualifiedName.orEmpty()
        return when {
            message.contains("Push rejected", ignoreCase = true) ->
                WacsPublishException(WacsPublishException.Reason.CONFLICT, e)
            message.contains("Cannot fast-forward", ignoreCase = true) ->
                WacsPublishException(WacsPublishException.Reason.CONFLICT, e)
            e is IOException || className.contains("Transport") || e.cause is IOException ->
                WacsPublishException(WacsPublishException.Reason.NETWORK, e)
            else -> WacsPublishException(WacsPublishException.Reason.UNKNOWN, e)
        }
    }

    private fun classifyLfsFailure(e: Exception): WacsPublishException {
        val message = e.message.orEmpty()
        return when {
            message.contains("integrity", ignoreCase = true) ->
                WacsPublishException(WacsPublishException.Reason.INTEGRITY, e)
            message.contains("HTTP 401") || message.contains("HTTP 403") ->
                WacsPublishException(WacsPublishException.Reason.AUTH_REJECTED, e)
            else -> WacsPublishException(WacsPublishException.Reason.NETWORK, e)
        }
    }
}
