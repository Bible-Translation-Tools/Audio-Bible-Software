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
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsChapterIngredient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient
import java.io.File

/**
 * M3: pull ONE chapter's audio (plus any companion timing/alignment ingredient) out of a repo
 * already opened by [CloneWacsRepo] — the low-bandwidth, per-chapter fetch the plan doc calls for
 * (§4/§8): a dropped connection costs one file, not the whole repo, and nothing downloads until the
 * user actually opens a chapter.
 *
 * Reuses the exact M0/M2 primitives, just in the download direction: [IWacsGitClient.listLfsPointers]
 * to find the chapter's pointer in the already-cloned tree (no network — pure local git-object read),
 * then [LfsBatchClient.download] to fetch + sha256-verify the real bytes (integrity is enforced
 * inside that client; see its KDoc). The chapter's companion file(s) — e.g. alignment/timing JSON —
 * are plain (non-LFS) content already checked out in the working tree, so they're just copied.
 */
class PullChapter(
    private val gitClient: IWacsGitClient,
    private val lfsClient: LfsBatchClient,
    private val session: WacsSession,
    private val appDirectories: IAppDirectories,
) {
    data class Result(
        val audioFile: File,
        val companionFiles: List<File>,
    )

    /**
     * Materializes [ingredient]'s audio (+ companions) from [workDir] (a [CloneWacsRepo.Result.workDir])
     * into a per-chapter cache directory under [IAppDirectories.cacheDirectory], keyed by repo +
     * book + chapter so re-pulling the same chapter overwrites in place rather than accumulating.
     */
    suspend fun pull(repo: ForgejoRepo, workDir: File, ingredient: WacsChapterIngredient): Result {
        val credential = session.credential
            ?: throw WacsPullException(WacsPullException.Reason.NOT_AUTHENTICATED)

        val destinationDir = File(
            appDirectories.cacheDirectory,
            "wacs-pull-materialized/${repo.owner.login}/${repo.name}/${ingredient.bookSlug}/${ingredient.chapterNumber}",
        ).apply { mkdirs() }

        val tracked = try {
            gitClient.listLfsPointers(workDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classifyGitFailure(e)
        }

        val trackedAudio = tracked.find { it.path == ingredient.audioPath }
            ?: throw WacsPullException(WacsPullException.Reason.CHAPTER_NOT_FOUND)

        val destAudio = File(destinationDir, trackedAudio.path.substringAfterLast('/'))
        try {
            lfsClient.download(
                "${repo.cloneUrl.trimEnd('/')}/info/lfs",
                credential,
                trackedAudio.pointer,
                destAudio,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classifyLfsFailure(e)
        }

        val companions = try {
            ingredient.companionPaths.mapNotNull { path ->
                val src = File(workDir, path)
                if (!src.exists()) return@mapNotNull null
                val dest = File(destinationDir, src.name)
                src.copyTo(dest, overwrite = true)
                dest
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPullException(WacsPullException.Reason.UNKNOWN, e)
        }

        return Result(destAudio, companions)
    }

    private fun classifyGitFailure(e: Exception): WacsPullException {
        val className = e::class.qualifiedName.orEmpty()
        return when {
            e is java.io.IOException || className.contains("Transport") || e.cause is java.io.IOException ->
                WacsPullException(WacsPullException.Reason.NETWORK, e)
            else -> WacsPullException(WacsPullException.Reason.UNKNOWN, e)
        }
    }

    private fun classifyLfsFailure(e: Exception): WacsPullException {
        val message = e.message.orEmpty()
        return when {
            message.contains("integrity", ignoreCase = true) ->
                WacsPullException(WacsPullException.Reason.INTEGRITY, e)
            message.contains("HTTP 401") || message.contains("HTTP 403") ->
                WacsPullException(WacsPullException.Reason.AUTH_REJECTED, e)
            else -> WacsPullException(WacsPullException.Reason.NETWORK, e)
        }
    }
}
