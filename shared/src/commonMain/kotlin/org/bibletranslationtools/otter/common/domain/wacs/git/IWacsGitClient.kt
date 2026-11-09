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
package org.bibletranslationtools.otter.common.domain.wacs.git

import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import java.io.File

/**
 * The git plumbing for WACS sync: clone/fetch/commit/push driven by JGit (pure Java — no `git`
 * binary), with LFS transfer handled separately by
 * [org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient]. Implementations MUST run
 * with host git config isolated (see [GitConfigIsolation]) so no ambient `filter.lfs.*` ever makes
 * JGit shell out to a system `git-lfs`; the working tree therefore holds LFS *pointers*, which is
 * what [listLfsPointers] reads and what [stage] stages.
 */
interface IWacsGitClient {

    /** An LFS-tracked file in the tree: its repo-relative [path] and the [pointer] committed there. */
    data class TrackedObject(val path: String, val pointer: LfsPointer)

    /** Clone [remoteUrl] (default branch [branch]) into [dir]. Checks out pointers, not audio. */
    suspend fun clone(
        remoteUrl: String,
        dir: File,
        credential: WacsCredential,
        branch: String = DEFAULT_BRANCH,
    )

    /** Fetch [branch] from origin and fast-forward the local branch. Returns true if it moved. */
    suspend fun fetchAndFastForward(
        dir: File,
        credential: WacsCredential,
        branch: String = DEFAULT_BRANCH,
    ): Boolean

    /**
     * Points the remote [remote] at [url] (adding it if needed), fetches its [branch] into
     * `refs/remotes/<remote>/<branch>`, and returns that branch's commit. Publishing fetches the
     * official repo as `upstream` this way. Pointers only: nothing here downloads LFS audio.
     */
    suspend fun fetchRemote(
        dir: File,
        remote: String,
        url: String,
        branch: String,
        credential: WacsCredential,
    ): String

    /** The commit [revision] (a branch, remote-tracking ref, or SHA-1) names, or null if none. */
    suspend fun resolve(dir: File, revision: String): String?

    /**
     * `git checkout -B <branch> <startPoint>`: makes [branch] point at [startPoint] and checks it
     * out, discarding whatever the working tree held.
     */
    suspend fun startBranch(dir: File, branch: String, startPoint: String)

    /** The repo-relative paths of the files under [directory] at [revision]. */
    suspend fun listFiles(dir: File, revision: String, directory: String): List<String>

    /** Stages [remove] as deleted and [add] (written by the caller) as added or changed. */
    suspend fun stage(dir: File, add: List<String>, remove: List<String> = emptyList())

    /** Commits what's staged; returns the new commit's SHA-1, or null if nothing differs from HEAD. */
    suspend fun commitStaged(dir: File, message: String, authorName: String, authorEmail: String): String?

    /**
     * Pushes the local [branch] to origin's branch of the same name, replacing it only if origin
     * still has it at [expectedRemote] (`--force-with-lease`); null means origin mustn't have the
     * branch yet. Throws if the remote rejects the update, e.g. because another device pushed.
     */
    suspend fun push(dir: File, credential: WacsCredential, branch: String, expectedRemote: String?)

    /** Read every LFS pointer committed at HEAD (repo-relative path + parsed pointer). */
    suspend fun listLfsPointers(dir: File): List<TrackedObject>

    companion object {
        const val DEFAULT_BRANCH = "master"
    }
}
