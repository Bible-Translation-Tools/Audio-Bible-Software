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
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoScope
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsScopeReader
import retrofit2.HttpException
import java.io.File
import java.io.IOException

/**
 * M3: open a repo in the `AudioTranslation` org for pull-as-source — clone it **read-only**
 * (pointers only; no fork, unlike [PublishChapterToWacs]'s fork-then-clone, since pulling never
 * writes back) into a stable per-repo cache directory, and read its `metadata.json` to learn what's
 * currently available ([WacsRepoScope]) so the UI can show book/chapter options WITHOUT downloading
 * any audio (the plan doc, §8, M3 step 2).
 *
 * The clone is cached under [IAppDirectories.cacheDirectory] rather than a one-shot temp dir (unlike
 * [PublishChapterToWacs]'s per-publish work dir) because a pull session browses a repo's chapters
 * one at a time — [PullChapter] needs the same working tree to still be there for each chapter the
 * user opens, and re-cloning per chapter would defeat the "low-bandwidth per-chapter fetch" point of
 * this milestone. Re-opening an already-cloned repo cheaply fast-forwards it instead of re-cloning;
 * if that fails for any reason (corrupted cache, first fast-forward ever hitting a diverged ref),
 * self-heals by wiping the cache dir and cloning fresh once rather than hard-failing the whole pull.
 */
class CloneWacsRepo(
    private val apiFactory: WacsApiFactory,
    private val gitClient: IWacsGitClient,
    private val session: WacsSession,
    private val config: WacsConfig,
    private val appDirectories: IAppDirectories,
) {
    data class Result(
        val repo: ForgejoRepo,
        /** The clone's working tree — pass to [PullChapter] to pull a chapter from it. */
        val workDir: File,
        val scope: WacsRepoScope,
    )

    suspend fun open(repoName: String): Result {
        val credential = session.credential
            ?: throw WacsPullException(WacsPullException.Reason.NOT_AUTHENTICATED)
        val host = session.host ?: config.baseUrl
        val api = apiFactory.create(host)
        val auth = credential.authorizationHeader()

        val repo = try {
            api.getRepo(config.org, repoName, auth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 404) throw WacsPullException(WacsPullException.Reason.REPO_NOT_FOUND, e)
            throw classifyHttp(e)
        } catch (e: IOException) {
            throw WacsPullException(WacsPullException.Reason.NETWORK, e)
        }

        val workDir = File(appDirectories.cacheDirectory, "wacs-pull/${config.org}/$repoName")
        try {
            if (File(workDir, ".git").isDirectory) {
                gitClient.fetchAndFastForward(workDir, credential, repo.defaultBranch)
            } else {
                cloneFresh(repo, workDir, credential)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Self-heal once: an existing cached clone that fails to update (corrupted cache,
            // diverged local ref) is wiped and re-cloned rather than failing the whole pull.
            try {
                cloneFresh(repo, workDir, credential)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                throw classifyGitFailure(e2)
            }
        }

        val metadataFile = File(workDir, WacsRepoLayout.METADATA_PATH)
        if (!metadataFile.exists()) throw WacsPullException(WacsPullException.Reason.NO_SCOPE)
        val scope = try {
            WacsScopeReader.read(metadataFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPullException(WacsPullException.Reason.NO_SCOPE, e)
        }

        return Result(repo, workDir, scope)
    }

    private suspend fun cloneFresh(repo: ForgejoRepo, workDir: File, credential: WacsCredential) {
        runCatching { workDir.deleteRecursively() }
        workDir.mkdirs()
        gitClient.clone(repo.cloneUrl, workDir, credential, repo.defaultBranch)
    }

    private fun classifyHttp(e: HttpException): WacsPullException = when {
        e.code() == 401 || e.code() == 403 -> WacsPullException(WacsPullException.Reason.AUTH_REJECTED, e)
        e.code() == 404 -> WacsPullException(WacsPullException.Reason.REPO_NOT_FOUND, e)
        e.code() >= 500 -> WacsPullException(WacsPullException.Reason.NETWORK, e)
        else -> WacsPullException(WacsPullException.Reason.UNKNOWN, e)
    }

    private fun classifyGitFailure(e: Exception): WacsPullException {
        val message = e.message.orEmpty()
        val className = e::class.qualifiedName.orEmpty()
        return when {
            e is IOException || className.contains("Transport") || e.cause is IOException ->
                WacsPullException(WacsPullException.Reason.NETWORK, e)
            else -> WacsPullException(WacsPullException.Reason.UNKNOWN, e)
        }
    }
}
