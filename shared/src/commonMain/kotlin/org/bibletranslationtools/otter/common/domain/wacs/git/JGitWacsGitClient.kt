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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.domain.wacs.WacsPlatform
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.WacsAndroidFS
import java.io.File

/**
 * JGit-backed [IWacsGitClient]. Installs [GitConfigIsolation] on construction so no host git config
 * (notably `filter.lfs.*`) leaks in — guaranteeing pointer-preserving, binary-free behavior on both
 * Android and Desktop. All JGit calls are blocking, so each runs on [Dispatchers.IO].
 *
 * On the Android runtime it uses [WacsAndroidFS], which implements file metadata via `java.io.File`
 * instead of `java.nio.file` POSIX permissions/attributes — Android's desugared nio provider fails on
 * `getPosixFilePermissions` (repo init) and `PosixFileAttributeView` (working-tree scans), so JGit's
 * default `FS_POSIX` path crashes there. On Desktop the platform-detected [FS] is used.
 */
class JGitWacsGitClient : IWacsGitClient {

    init {
        GitConfigIsolation.install()
    }

    /** Fail fast (rather than a cryptic JGit error) when git sync is not supported on this platform. */
    private fun requireSupported() = check(WacsPlatform.isGitSyncSupported) {
        "WACS git sync requires Android 8 (API 26)+ on this device."
    }

    /**
     * A FRESH filesystem per JGit operation. On Android, reusing one [WacsAndroidFS] instance across
     * a clone and a later reopen corrupts pack access (reopen throws MissingObjectException even
     * though the pack is on disk and a fresh instance reads it fine); a new instance per operation
     * avoids it. On Desktop the platform singleton [FS.DETECTED] is safe to reuse and returned as-is.
     */
    private fun newFs(): FS = if (isAndroidRuntime()) WacsAndroidFS() else FS.DETECTED

    override suspend fun clone(
        remoteUrl: String,
        dir: File,
        credential: WacsCredential,
        branch: String,
    ) = withContext(Dispatchers.IO) {
        requireSupported()
        Git.cloneRepository()
            .setURI(remoteUrl)
            .setDirectory(dir)
            .setBranch(branch)
            .setFs(newFs())
            .setCredentialsProvider(credential.toProvider())
            .call()
            .use { /* close immediately; callers reopen with open(dir) when needed */ }
    }

    override suspend fun fetchAndFastForward(
        dir: File,
        credential: WacsCredential,
        branch: String,
    ): Boolean = withContext(Dispatchers.IO) {
        requireSupported()
        open(dir).use { git ->
            git.fetch().setCredentialsProvider(credential.toProvider()).call()
            git.fastForwardTo("${Constants.DEFAULT_REMOTE_NAME}/$branch")
        }
    }

    override suspend fun fetchRemote(
        dir: File,
        remote: String,
        url: String,
        branch: String,
        credential: WacsCredential,
    ): String = withContext(Dispatchers.IO) {
        requireSupported()
        open(dir).use { git ->
            // `git remote add <remote> <url>` is just these two config keys; setting them directly
            // also repoints a remote that already exists.
            val repoConfig = git.repository.config
            repoConfig.setString("remote", remote, "url", url)
            repoConfig.setString("remote", remote, "fetch", "+refs/heads/*:refs/remotes/$remote/*")
            repoConfig.save()

            val tracking = "refs/remotes/$remote/$branch"
            git.fetch()
                .setRemote(remote)
                .setRefSpecs(RefSpec("+refs/heads/$branch:$tracking"))
                .setCredentialsProvider(credential.toProvider())
                .call()
            git.repository.resolve(tracking)?.name ?: error("$remote has no branch $branch")
        }
    }

    override suspend fun resolve(dir: File, revision: String): String? = withContext(Dispatchers.IO) {
        requireSupported()
        open(dir).use { git -> git.repository.resolve(revision)?.name }
    }

    override suspend fun startBranch(dir: File, branch: String, startPoint: String) = withContext(Dispatchers.IO) {
        requireSupported()
        open(dir).use { git ->
            val start = git.repository.resolve(startPoint) ?: error("No $startPoint to start $branch from")
            // Detach first: git won't force-move the branch that's checked out.
            git.checkout().setName(start.name).setForced(true).call()
            git.branchCreate().setName(branch).setStartPoint(start.name).setForce(true).call()
            git.checkout().setName(branch).setForced(true).call()
            Unit
        }
    }

    override suspend fun listFiles(dir: File, revision: String, directory: String): List<String> =
        withContext(Dispatchers.IO) {
            requireSupported()
            open(dir).use { git ->
                val repo = git.repository
                val commit = repo.resolve(revision) ?: error("No $revision")
                RevWalk(repo).use { walk ->
                    TreeWalk(repo).use { tw ->
                        tw.addTree(walk.parseCommit(commit).tree)
                        tw.isRecursive = true
                        tw.filter = PathFilter.create(directory.trimEnd('/'))
                        buildList { while (tw.next()) add(tw.pathString) }
                    }
                }
            }
        }

    override suspend fun restoreFiles(dir: File, revision: String, paths: List<String>) = withContext(Dispatchers.IO) {
        requireSupported()
        if (paths.isEmpty()) return@withContext
        open(dir).use { git ->
            git.checkout().setStartPoint(revision).addPaths(paths).call()
            Unit
        }
    }

    override suspend fun stage(dir: File, add: List<String>, remove: List<String>) = withContext(Dispatchers.IO) {
        requireSupported()
        open(dir).use { git ->
            if (remove.isNotEmpty()) {
                val rm = git.rm()
                remove.forEach { rm.addFilepattern(it) }
                rm.call()
            }
            if (add.isNotEmpty()) {
                val stageAdds = git.add()
                add.forEach { stageAdds.addFilepattern(it) }
                stageAdds.call()
            }
            Unit
        }
    }

    override suspend fun commitStaged(dir: File, message: String, authorName: String, authorEmail: String): String? =
        withContext(Dispatchers.IO) {
            requireSupported()
            open(dir).use { git ->
                val status = git.status().call()
                if (status.added.isEmpty() && status.changed.isEmpty() && status.removed.isEmpty()) {
                    return@use null
                }
                git.commit()
                    .setMessage(message)
                    .setAuthor(authorName, authorEmail)
                    .setCommitter(authorName, authorEmail)
                    .setSign(false)
                    .call()
                    .name
            }
        }

    /** Fast-forward-merge the currently checked-out branch onto [remoteRef] (e.g. `origin/master`). */
    private fun Git.fastForwardTo(remoteRef: String): Boolean {
        val target = repository.resolve(remoteRef) ?: error("No $remoteRef to fast-forward from")
        val result = merge()
            .include(target)
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .call()
        return when (result.mergeStatus) {
            MergeResult.MergeStatus.FAST_FORWARD -> true
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> false
            else -> error("Cannot fast-forward to $remoteRef: ${result.mergeStatus} (someone else changed it)")
        }
    }

    override suspend fun push(
        dir: File,
        credential: WacsCredential,
        branch: String,
        expectedRemote: String?,
    ) = withContext(Dispatchers.IO) {
        requireSupported()
        open(dir).use { git ->
            val ref = "refs/heads/$branch"
            val results = git.push()
                .setCredentialsProvider(credential.toProvider())
                .setRefSpecs(RefSpec("$ref:$ref").setForceUpdate(true))
                // The all-zero id is git's "no such ref": a new branch mustn't already exist.
                .setRefLeaseSpecs(RefLeaseSpec(ref, expectedRemote ?: ObjectId.zeroId().name))
                .call()
            val rejected = results.flatMap { it.remoteUpdates }.filterNot {
                it.status == RemoteRefUpdate.Status.OK || it.status == RemoteRefUpdate.Status.UP_TO_DATE
            }
            if (rejected.isNotEmpty()) {
                val detail = rejected.joinToString { "${it.remoteName}: ${it.status} ${it.message ?: ""}".trim() }
                error("Push rejected -> $detail")
            }
        }
    }

    override suspend fun listLfsPointers(dir: File): List<IWacsGitClient.TrackedObject> =
        withContext(Dispatchers.IO) {
        requireSupported()
            open(dir).use { git ->
                val repo: Repository = git.repository
                val head = repo.resolve(Constants.HEAD) ?: return@use emptyList()
                val out = mutableListOf<IWacsGitClient.TrackedObject>()
                repo.newObjectReader().use { reader ->
                    RevWalk(repo).use { walk ->
                        val tree = walk.parseCommit(head).tree
                        TreeWalk(repo).use { tw ->
                            tw.addTree(tree)
                            tw.isRecursive = true
                            while (tw.next()) {
                                val loader = reader.open(tw.getObjectId(0))
                                // Only small blobs can be pointers; skip large real content cheaply.
                                if (loader.size > 1024) continue
                                LfsPointer.parse(loader.bytes)?.let {
                                    out += IWacsGitClient.TrackedObject(tw.pathString, it)
                                }
                            }
                        }
                    }
                }
                out
            }
        }

    /** Open an existing clone with the platform-appropriate [fs]. */
    private fun open(dir: File): Git = Git.open(dir, newFs())

    /** WACS takes a token as the git password, so every credential is a username and a secret. */
    private fun WacsCredential.toProvider(): CredentialsProvider = UsernamePasswordCredentialsProvider(username, gitPassword)

    private companion object {
        fun isAndroidRuntime(): Boolean =
            System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == true ||
                System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true
    }
}
