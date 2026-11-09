package org.bibletranslationtools.otter.common.domain.wacs.fake

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.bibletranslationtools.otter.common.domain.wacs.api.CreatePullRequestRequest
import org.bibletranslationtools.otter.common.domain.wacs.api.CreateRepoRequest
import org.bibletranslationtools.otter.common.domain.wacs.api.CreateTokenRequest
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoAccessToken
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoOwner
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoPrBranchInfo
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoPrRepo
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoPullRequest
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoUser
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.git.GitConfigIsolation
import org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.git.JGitWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-process stand-in for a WACS host (Forgejo with git-LFS) and the audio publishing pipeline
 * in front of it, so WACS sync is tested without a server: the tests have to pass in CI from this
 * repo alone.
 *
 * - REST, LFS and the pipeline: an OkHttp interceptor on [http] answers what
 *   [ForgejoApi][org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoApi], [LfsBatchClient]
 *   and [PublishPipelineApi][org.bibletranslationtools.otter.common.domain.wacs.api.PublishPipelineApi]
 *   ask, from memory. Nothing is sent over a network; [apiFactory] and [lfsClient] are built on [http].
 * - git: each repo's remote is a bare repository under [root]. [gitClient] is the real
 *   [JGitWacsGitClient], with a repo's clone URL pointed at its bare repository, so clone, fetch,
 *   commit and push run for real.
 *
 * It models the slice of Forgejo the client uses, with Forgejo's status codes where the client acts
 * on them: 401 for a wrong password or token, 404 for a missing repo or LFS object, 409 for a repo
 * or fork that already exists or a duplicate pull request, 422 for an upload whose bytes don't match
 * its oid. The pipeline's `prepare` follows its `PUBLISH_API.md`: naming, idempotence and error
 * codes. git itself doesn't check credentials here; the REST calls in front of it do.
 */
class FakeWacs(private val root: File) {

    val host = "http://$HOST/"

    /** Where the publish pipeline answers. */
    val pipelineUrl = "http://$PIPELINE_HOST/"

    /** Every request answered, oldest first. */
    val requests: List<RecordedRequest> get() = recorded.toList()

    /** A request answered: its method, host, path (no query), and headers. */
    data class RecordedRequest(val method: String, val host: String, val path: String, val headers: Headers)

    /** One commit on a branch: its message and the files it changed. */
    data class CommitInfo(val sha: String, val message: String, val changedPaths: Set<String>)

    /** When true, every request fails as if the host can't be reached. */
    @Volatile
    var unreachable = false

    /**
     * When set, LFS transfers go to this separate object-store host with a presigned header of their
     * own, as Forgejo does with `SERVE_DIRECT`, rather than back through the WACS host.
     */
    @Volatile
    var objectStoreHost: String? = null

    /** The language tags the pipeline knows; any other well-formed tag is `unknown_language`. */
    @Volatile
    var knownLanguages: Set<String> = setOf("en", "fr", "es-419")

    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()
    val apiFactory = WacsApiFactory(http)
    val lfsClient = LfsBatchClient(http)
    val gitClient: IWacsGitClient

    private data class Account(val id: Long, val login: String, val password: String?)

    private class HostedRepo(
        val info: ForgejoRepo,
        val remote: File,
        val lfs: MutableMap<String, ByteArray>,
        /** The repo this one is a fork of, `owner/name`. */
        val parent: String? = null,
    )

    private class HostedPull(val target: String, var pull: ForgejoPullRequest, val headRepo: String)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val accounts = mutableMapOf<String, Account>()
    private val tokens = mutableMapOf<String, String>()
    private val repos = linkedMapOf<String, HostedRepo>()
    private val pulls = mutableListOf<HostedPull>()
    private val failures = mutableListOf<Failure>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()
    private var nextId = 1L

    private class Failure(val method: String, val host: String, val path: String, val code: Int, var times: Int)

    init {
        // Before any JGit use here, so a developer's global git-lfs filter can't smudge the seeds.
        GitConfigIsolation.install()
        gitClient = RemotesOnDisk(JGitWacsGitClient())
    }

    // ── Seeding and inspecting ─────────────────────────────────────────────────────────────

    /** Adds an account that can sign in with [password], and returns its credential. */
    @Synchronized
    fun addUser(login: String, password: String): WacsCredential.Basic {
        accounts[login] = Account(nextId++, login, password)
        return WacsCredential.Basic(login, password)
    }

    /** An access token for [login], as signing in would create. */
    @Synchronized
    fun tokenFor(login: String): WacsCredential.Token {
        require(login in accounts)
        val token = "token-${nextId++}"
        tokens[token] = login
        return WacsCredential.Token(login, token)
    }

    /** Adds an organization, which owns repos but can't sign in. */
    @Synchronized
    fun addOrg(name: String) {
        accounts[name] = Account(nextId++, name, null)
    }

    /**
     * Creates `owner/name` with one commit on `master` holding [files] and, as LFS pointers,
     * [lfsFiles] (whose bytes go to the repo's LFS store). With neither, the repo is empty.
     */
    @Synchronized
    fun createRepo(
        owner: String,
        name: String,
        files: Map<String, ByteArray> = mapOf("README.md" to "# $name\n".encodeToByteArray()),
        lfsFiles: Map<String, ByteArray> = emptyMap(),
    ): ForgejoRepo {
        val account = requireNotNull(accounts[owner]) { "no account or org named $owner" }
        val fullName = "$owner/$name"
        check(fullName !in repos) { "$fullName already exists" }
        val remote = File(root, "remotes/$fullName.git")
        Git.init().setBare(true).setInitialBranch(BRANCH).setDirectory(remote).call().close()
        val empty = files.isEmpty() && lfsFiles.isEmpty()
        val info = repoInfo(account, name, fork = false, empty = empty)
        repos[fullName] = HostedRepo(info, remote, mutableMapOf())
        if (!empty) pushCommit(fullName, files, lfsFiles, "Initial commit")
        return info
    }

    /** Commits [files] and [lfsFiles] on top of `owner/name`'s `master`, as another contributor would. */
    @Synchronized
    fun pushCommit(
        fullName: String,
        files: Map<String, ByteArray> = emptyMap(),
        lfsFiles: Map<String, ByteArray> = emptyMap(),
        message: String = "Another contributor's change",
    ) {
        val hosted = requireNotNull(repos[fullName]) { "no repo $fullName" }
        val work = File(root, "work-${UUID.randomUUID()}")
        try {
            val git = if (Git.lsRemoteRepository().setRemote(hosted.remote.toURI().toString()).call().isEmpty()) {
                Git.init().setInitialBranch(BRANCH).setDirectory(work).call()
            } else {
                Git.cloneRepository().setURI(hosted.remote.toURI().toString()).setBranch(BRANCH).setDirectory(work).call()
            }
            git.use {
                files.forEach { (path, bytes) -> File(work, path).apply { parentFile.mkdirs() }.writeBytes(bytes) }
                lfsFiles.forEach { (path, bytes) ->
                    val pointer = LfsPointer(sha256(bytes), bytes.size.toLong())
                    hosted.lfs[pointer.oid] = bytes
                    File(work, path).apply { parentFile.mkdirs() }.writeText(pointer.serialize())
                }
                it.add().addFilepattern(".").call()
                it.commit().setMessage(message).setAuthor("someone", "someone@example.org").setSign(false).call()
                it.push().setRemote(hosted.remote.toURI().toString()).setRefSpecs(RefSpec("HEAD:refs/heads/$BRANCH")).call()
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Synchronized
    fun repo(fullName: String): ForgejoRepo? = repos[fullName]?.info

    /** The repo `owner/name` is a fork of, or null. */
    @Synchronized
    fun parentOf(fullName: String): String? = repos[fullName]?.parent

    @Synchronized
    fun pullRequests(fullName: String): List<ForgejoPullRequest> = pulls.filter { it.target == fullName }.map { it.pull }

    @Synchronized
    fun lfsObject(fullName: String, oid: String): ByteArray? = repos[fullName]?.lfs?.get(oid)

    /** Replaces the bytes stored for [oid], so they no longer hash to it. */
    @Synchronized
    fun corruptLfsObject(fullName: String, oid: String) {
        val lfs = requireNotNull(repos[fullName]).lfs
        val bytes = requireNotNull(lfs[oid]).copyOf()
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex] + 1).toByte()
        lfs[oid] = bytes
    }

    /** A fresh checkout of `owner/name`'s [branch], to look at what was pushed. */
    @Synchronized
    fun checkout(fullName: String, branch: String = BRANCH): File {
        val dir = File(root, "checkout-${UUID.randomUUID()}")
        Git.cloneRepository().setURI(requireNotNull(repos[fullName]).remote.toURI().toString())
            .setBranch(branch).setDirectory(dir).call().close()
        return dir
    }

    /** The branches `owner/name` has. */
    @Synchronized
    fun branches(fullName: String): List<String> = Git.open(requireNotNull(repos[fullName]).remote).use { git ->
        git.branchList().call().map { it.name.removePrefix(Constants.R_HEADS) }
    }

    /** The log of `owner/name`'s [branch], newest first, as commit messages. */
    @Synchronized
    fun log(fullName: String, branch: String = BRANCH): List<String> = commits(fullName, branch).map { it.message }

    /** The commits on `owner/name`'s [branch], newest first, each with the files it changed. */
    @Synchronized
    fun commits(fullName: String, branch: String = BRANCH): List<CommitInfo> =
        Git.open(requireNotNull(repos[fullName]).remote).use { git ->
            val repo = git.repository
            val tip = repo.resolve("refs/heads/$branch") ?: return@use emptyList()
            RevWalk(repo).use { walk ->
                walk.markStart(walk.parseCommit(tip))
                walk.map { commit -> CommitInfo(commit.name, commit.fullMessage.trimEnd(), changedPaths(git, walk, commit)) }
            }
        }

    /**
     * Merges pull request [number] on `owner/name` as a reviewer would: a fast-forward when `master`
     * hasn't moved since the branch was cut, otherwise a merge commit, so pull requests merge in any
     * order. The pull request is closed; the head branch stays on the fork, as WACS leaves it.
     */
    @Synchronized
    fun mergePullRequest(fullName: String, number: Long) {
        val hosted = pulls.single { it.target == fullName && it.pull.number == number }
        val target = requireNotNull(repos[fullName])
        val head = requireNotNull(repos[hosted.headRepo])
        Git.open(target.remote).use { git ->
            val repo = git.repository
            val pullRef = "refs/pull/$number/head"
            git.fetch().setRemote(head.remote.toURI().toString())
                .setRefSpecs(RefSpec("+refs/heads/${hosted.pull.head!!.ref}:$pullRef")).call()
            RevWalk(repo).use { walk ->
                val ours = walk.parseCommit(repo.resolve("refs/heads/$BRANCH"))
                val theirs = walk.parseCommit(repo.resolve(pullRef))
                val merged = if (walk.isMergedInto(ours, theirs)) {
                    theirs.id
                } else {
                    val merger = MergeStrategy.RECURSIVE.newMerger(repo, true)
                    check(merger.merge(ours, theirs)) { "pull request #$number conflicts with $BRANCH" }
                    repo.newObjectInserter().use { inserter ->
                        val reviewer = PersonIdent("reviewer", "reviewer@example.org")
                        val commit = CommitBuilder().apply {
                            setTreeId(merger.resultTreeId)
                            setParentIds(ours, theirs)
                            author = reviewer
                            committer = reviewer
                            message = "Merge pull request #$number"
                        }
                        inserter.insert(commit).also { inserter.flush() }
                    }
                }
                val update = repo.updateRef("refs/heads/$BRANCH").apply {
                    setExpectedOldObjectId(ours)
                    setNewObjectId(merged)
                }
                val result = update.update()
                check(result == RefUpdate.Result.FAST_FORWARD || result == RefUpdate.Result.FORCED) { "merging #$number: $result" }
            }
        }
        target.lfs.putAll(head.lfs)
        hosted.pull = hosted.pull.copy(state = "closed")
    }

    /** Answers [method] [path] (the URL's encoded path) on [host] with [code], [times] times. */
    @Synchronized
    fun fail(method: String, path: String, code: Int, host: String = HOST, times: Int = Int.MAX_VALUE) {
        failures += Failure(method, host, path, code, times)
    }

    fun cloneUrl(owner: String, name: String) = "$host$owner/$name.git"

    private fun changedPaths(git: Git, walk: RevWalk, commit: RevCommit): Set<String> {
        val repo = git.repository
        val parent = commit.parents.firstOrNull()?.let { walk.parseCommit(it) }
        if (parent == null) {
            return TreeWalk(repo).use { tw ->
                tw.addTree(commit.tree)
                tw.isRecursive = true
                buildSet { while (tw.next()) add(tw.pathString) }
            }
        }
        return git.diff().setOldTree(treeOf(repo, parent)).setNewTree(treeOf(repo, commit)).call()
            .map { if (it.changeType == DiffEntry.ChangeType.DELETE) it.oldPath else it.newPath }.toSet()
    }

    private fun treeOf(repo: org.eclipse.jgit.lib.Repository, commit: RevCommit) =
        org.eclipse.jgit.treewalk.CanonicalTreeParser().apply { repo.newObjectReader().use { reset(it, commit.tree) } }

    // ── git ────────────────────────────────────────────────────────────────────────────────

    /** The real JGit client, with a hosted repo's clone URL replaced by its bare repository on disk. */
    private inner class RemotesOnDisk(private val jgit: IWacsGitClient) : IWacsGitClient by jgit {
        override suspend fun clone(remoteUrl: String, dir: File, credential: WacsCredential, branch: String) =
            jgit.clone(remoteFor(remoteUrl), dir, credential, branch)

        override suspend fun fetchRemote(dir: File, remote: String, url: String, branch: String, credential: WacsCredential) =
            jgit.fetchRemote(dir, remote, remoteFor(url), branch, credential)
    }

    @Synchronized
    private fun remoteFor(cloneUrl: String): String {
        if (unreachable) throw IOException("$HOST can't be reached")
        // Never hand JGit the http URL: it would try to reach it.
        val hosted = repos.values.firstOrNull { it.info.cloneUrl == cloneUrl }
            ?: throw IOException("$cloneUrl: repository not found")
        return hosted.remote.toURI().toString()
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────────────────

    @Synchronized
    private fun answer(request: Request): Response {
        val url = request.url()
        recorded += RecordedRequest(request.method(), url.host(), url.encodedPath(), request.headers())
        if (unreachable) throw IOException("${url.host()} can't be reached")
        failures.firstOrNull { it.method == request.method() && it.host == url.host() && it.path == url.encodedPath() && it.times > 0 }
            ?.let { failure ->
                failure.times--
                return request.respond(failure.code, """{"error":"injected","message":"injected failure"}""")
            }

        if (url.host() == objectStoreHost) return objectStore(request)
        if (url.host() == PIPELINE_HOST) return pipeline(request)
        check(url.host() == HOST) { "FakeWacs only answers $HOST, not ${url.host()}" }

        val segments = url.pathSegments().filter { it.isNotEmpty() }
        if (segments.size == 5 && segments.take(3) == listOf("api", "v1", "users") && segments[4] == "tokens") {
            return createToken(request, segments[3])
        }
        val isLfs = segments.size >= 5 && segments[1].endsWith(".git") && segments[2] == "info" && segments[3] == "lfs"
        // Like Forgejo, LFS reads `token`/`Bearer` as its own LFS tokens; an access token has to come as Basic.
        val account = signedIn(request, acceptsTokenHeader = !isLfs)
            ?: return request.respond(401, """{"message":"unauthorized"}""")
        return when {
            segments.take(2) == listOf("api", "v1") -> api(request, account, segments.drop(2))
            isLfs -> lfs(request, "${segments[0]}/${segments[1].removeSuffix(".git")}", segments.drop(4))
            else -> request.respond(404)
        }
    }

    private fun api(request: Request, account: Account, path: List<String>): Response {
        val method = request.method()
        return when {
            path == listOf("user") && method == "GET" ->
                request.respond(200, json.encodeToString(ForgejoUser.serializer(), ForgejoUser(account.id, account.login, email = "${account.login}@example.org")))
            path == listOf("user", "repos") && method == "GET" ->
                request.respondPage(repos.values.filter { it.info.owner.login == account.login }.map { it.info }, ForgejoRepo.serializer())
            path == listOf("user", "repos") && method == "POST" -> createRepo(request, account)
            path.size == 3 && path[0] == "orgs" && path[2] == "repos" && method == "GET" ->
                request.respondPage(repos.values.filter { it.info.owner.login == path[1] }.map { it.info }, ForgejoRepo.serializer())
            path.size >= 3 && path[0] == "repos" -> {
                val fullName = "${path[1]}/${path[2]}"
                val hosted = repos[fullName] ?: return request.respond(404, """{"message":"repo not found"}""")
                when {
                    path.size == 3 && method == "GET" ->
                        request.respond(200, json.encodeToString(ForgejoRepo.serializer(), hosted.info))
                    path.getOrNull(3) == "forks" && method == "POST" -> {
                        val name = request.bodyText().takeIf { it.isNotBlank() }
                            ?.let { json.parseToJsonElement(it).jsonObject["name"]?.jsonPrimitive?.content }
                        fork(account, hosted, name ?: hosted.info.name)
                            ?.let { request.respond(202, json.encodeToString(ForgejoRepo.serializer(), it)) }
                            ?: request.respond(409, """{"message":"repository is already forked by user"}""")
                    }
                    path.size == 4 && path[3] == "pulls" && method == "GET" -> {
                        val state = request.url().queryParameter("state") ?: "open"
                        val list = pulls.filter { it.target == fullName && (state == "all" || it.pull.state == state) }.map { it.pull }
                        request.respondPage(list, ForgejoPullRequest.serializer())
                    }
                    path.size == 4 && path[3] == "pulls" && method == "POST" -> openPull(request, hosted)
                    else -> request.respond(404)
                }
            }
            else -> request.respond(404)
        }
    }

    /** Forgejo creates tokens only for a password, never for another token. */
    private fun createToken(request: Request, login: String): Response {
        val account = accounts[login]
        val password = account?.password
        if (password == null || request.header("Authorization") != WacsCredential.Basic(login, password).authorizationHeader()) {
            return request.respond(401, """{"message":"auth required"}""")
        }
        val body = json.decodeFromString(CreateTokenRequest.serializer(), request.bodyText())
        val token = "token-${nextId++}"
        tokens[token] = login
        return request.respond(201, json.encodeToString(ForgejoAccessToken.serializer(), ForgejoAccessToken(nextId++, body.name, token)))
    }

    private fun createRepo(request: Request, account: Account): Response {
        val body = json.decodeFromString(CreateRepoRequest.serializer(), request.bodyText())
        val fullName = "${account.login}/${body.name}"
        if (fullName in repos) return request.respond(409, """{"message":"repository already exists"}""")
        val remote = File(root, "remotes/$fullName.git")
        Git.init().setBare(true).setInitialBranch(body.defaultBranch).setDirectory(remote).call().close()
        val info = repoInfo(account, body.name, fork = false, empty = !body.autoInit)
        repos[fullName] = HostedRepo(info, remote, mutableMapOf())
        if (body.autoInit) pushCommit(fullName, mapOf("README.md" to "# ${body.name}\n".encodeToByteArray()), message = "Initial commit")
        return request.respond(201, json.encodeToString(ForgejoRepo.serializer(), info))
    }

    /** Forks [parent] into [account] as [name], or returns null if that name is taken. */
    private fun fork(account: Account, parent: HostedRepo, name: String): ForgejoRepo? {
        val fullName = "${account.login}/$name"
        if (fullName in repos) return null
        val remote = File(root, "remotes/$fullName.git")
        parent.remote.copyRecursively(remote)
        val info = repoInfo(account, name, fork = true, empty = false)
        repos[fullName] = HostedRepo(info, remote, parent.lfs.toMutableMap(), parent = parent.info.fullName)
        return info
    }

    private fun openPull(request: Request, target: HostedRepo): Response {
        val body = json.decodeFromString(CreatePullRequestRequest.serializer(), request.bodyText())
        val headOwner = body.head.substringBefore(':')
        val headRef = body.head.substringAfter(':')
        // The head is the owner's fork of the target, whatever it's named.
        val head = repos.values.firstOrNull { it.info.owner.login == headOwner && it.parent == target.info.fullName }
            ?: return request.respond(422, """{"message":"head repository not found"}""")
        if (headRef !in Git.open(head.remote).use { git -> git.branchList().call().map { it.name.removePrefix(Constants.R_HEADS) } }) {
            return request.respond(404, """{"message":"head branch not found"}""")
        }
        val duplicate = pulls.any {
            it.target == target.info.fullName && it.pull.state == "open" &&
                it.headRepo == head.info.fullName && it.pull.head?.ref == headRef && it.pull.base?.ref == body.base
        }
        if (duplicate) return request.respond(409, """{"message":"pull request already exists"}""")
        val number = pulls.count { it.target == target.info.fullName } + 1L
        val pull = ForgejoPullRequest(
            number = number,
            htmlUrl = "$host${target.info.fullName}/pulls/$number",
            state = "open",
            title = body.title,
            body = body.body,
            head = ForgejoPrBranchInfo(headRef, head.info.id, ForgejoPrRepo(head.info.fullName)),
            base = ForgejoPrBranchInfo(body.base, target.info.id, ForgejoPrRepo(target.info.fullName)),
        )
        pulls += HostedPull(target.info.fullName, pull, head.info.fullName)
        return request.respond(201, json.encodeToString(ForgejoPullRequest.serializer(), pull))
    }

    // ── The publish pipeline ───────────────────────────────────────────────────────────────

    /** `POST /v1/publish/prepare`, per the pipeline's `PUBLISH_API.md` §3 and §4. */
    private fun pipeline(request: Request): Response {
        if (request.method() != "POST" || request.url().encodedPath() != "/v1/publish/prepare") return request.respond(404)
        val token = Regex("^(?:token|bearer)\\s+(\\S+)$", RegexOption.IGNORE_CASE)
            .find(request.header("Authorization").orEmpty())?.groupValues?.get(1)
        val user = token?.let { tokens[it] }?.let { accounts[it] }
            ?: return request.pipelineError(401, "unauthorized", "send \"Authorization: token <your WACS token>\"")
        val body = runCatching { json.parseToJsonElement(request.bodyText()).jsonObject }.getOrNull()
            ?: return request.pipelineError(400, "bad_json", "the body isn't JSON")
        val language = body["language"]?.jsonPrimitive?.content?.trim()?.lowercase()
        val translation = body["translation"]?.jsonPrimitive?.content?.trim()?.lowercase()
        val edition = body["edition"]?.jsonPrimitive?.int ?: 1
        if (language == null || translation == null) return request.pipelineError(400, "bad_request", "language and translation are required")
        if (!Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(language)) {
            return request.pipelineError(400, "bad_language", "\"$language\" isn't an IETF language tag")
        }
        if (!Regex("^[a-z0-9-]+$").matches(translation)) {
            return request.pipelineError(400, "bad_translation", "translation \"$translation\" must match /^[a-z0-9-]+$/")
        }
        if (language !in knownLanguages) return request.pipelineError(422, "unknown_language", "\"$language\" is not a known IETF language tag")

        val name = "${language}_$translation" + if (edition > 1) "_${edition}e" else ""
        if (ORG !in accounts) addOrg(ORG)
        val officialName = "$ORG/$name"
        val officialCreated = officialName !in repos
        if (officialCreated) {
            createRepo(
                ORG, name,
                files = mapOf(
                    WacsRepoLayout.GITATTRIBUTES_PATH to WacsRepoLayout.AUDIO_EXTENSIONS
                        .joinToString("") { WacsRepoLayout.gitattributesLine(it) + "\n" }.encodeToByteArray(),
                    WacsRepoLayout.METADATA_PATH to """{"ingredients":{}}""".encodeToByteArray(),
                ),
            )
        }
        val official = requireNotNull(repos[officialName])
        // Forgejo allows one fork per user per repo, whatever it's named.
        val existingFork = repos.values.firstOrNull { it.info.owner.login == user.login && it.parent == officialName }
        val fork = existingFork?.info
            ?: fork(user, official, "${name}_audio")
            ?: return request.pipelineError(409, "fork_name_taken", "${user.login}/${name}_audio exists and isn't a fork of $officialName")
        val response = buildJsonObject {
            putJsonObject("official") {
                put("owner", ORG)
                put("name", name)
                put("clone_url", official.info.cloneUrl)
                put("default_branch", BRANCH)
                put("created", officialCreated)
            }
            putJsonObject("fork") {
                put("owner", fork.owner.login)
                put("name", fork.name)
                put("clone_url", fork.cloneUrl)
                put("created", existingFork == null)
            }
        }
        return request.respond(200, response.toString())
    }

    private fun Request.pipelineError(code: Int, error: String, message: String): Response =
        respond(code, buildJsonObject { put("error", error); put("message", message) }.toString())

    // ── LFS ────────────────────────────────────────────────────────────────────────────────

    /** `<owner>/<repo>.git/info/lfs/...`: the batch endpoint, transfers, and verify. */
    private fun lfs(request: Request, fullName: String, path: List<String>): Response {
        val hosted = repos[fullName] ?: return request.respond(404, """{"message":"repo not found"}""")
        return when {
            path == listOf("objects", "batch") && request.method() == "POST" -> batch(request, fullName, hosted)
            path == listOf("verify") && request.method() == "POST" -> {
                val oid = json.parseToJsonElement(request.bodyText()).jsonObject["oid"]!!.jsonPrimitive.content
                request.respond(if (oid in hosted.lfs) 200 else 404)
            }
            path.size == 2 && path[0] == "objects" -> transfer(request, hosted, path[1])
            else -> request.respond(404)
        }
    }

    private fun batch(request: Request, fullName: String, hosted: HostedRepo): Response {
        val body = json.parseToJsonElement(request.bodyText()).jsonObject
        val operation = body["operation"]!!.jsonPrimitive.content
        val objects = body["objects"]!!.jsonArray.map { it.jsonObject }
        val response = buildJsonObject {
            put("transfer", "basic")
            put("objects", buildJsonArray {
                objects.forEach { obj ->
                    val oid = obj["oid"]!!.jsonPrimitive.content
                    val size = obj["size"]!!.jsonPrimitive.long
                    add(buildJsonObject {
                        put("oid", oid)
                        put("size", size)
                        val present = oid in hosted.lfs
                        when {
                            operation == "download" && !present -> putJsonObject("error") {
                                put("code", 404)
                                put("message", "Object does not exist")
                            }
                            operation == "download" -> putJsonObject("actions") { put("download", transferAction(fullName, oid)) }
                            !present -> putJsonObject("actions") {
                                put("upload", transferAction(fullName, oid))
                                putJsonObject("verify") { put("href", "$host$fullName.git/info/lfs/verify") }
                            }
                            // Already stored: no actions, so the client uploads nothing.
                        }
                    })
                }
            })
        }
        return request.respond(200, response.toString(), LFS_JSON)
    }

    private fun transferAction(fullName: String, oid: String): JsonObject = buildJsonObject {
        val store = objectStoreHost
        if (store == null) {
            put("href", "$host$fullName.git/info/lfs/objects/$oid")
        } else {
            put("href", "http://$store/$fullName/$oid")
            putJsonObject("header") { put(PRESIGNED_HEADER, "signed") }
        }
    }

    private fun transfer(request: Request, hosted: HostedRepo, oid: String): Response = when (request.method()) {
        "GET" -> hosted.lfs[oid]?.let { request.respondBytes(it) } ?: request.respond(404)
        "PUT" -> {
            val bytes = request.bodyBytes()
            if (sha256(bytes) != oid) {
                request.respond(422, """{"message":"content does not match its oid"}""")
            } else {
                hosted.lfs[oid] = bytes
                request.respond(200)
            }
        }
        else -> request.respond(405)
    }

    /** The `SERVE_DIRECT` object store: only the presigned header is accepted; WACS credentials aren't. */
    private fun objectStore(request: Request): Response {
        if (request.header(PRESIGNED_HEADER) != "signed") return request.respond(403)
        val segments = request.url().pathSegments()
        val hosted = repos["${segments[0]}/${segments[1]}"] ?: return request.respond(404)
        return transfer(request, hosted, segments[2])
    }

    /**
     * The account a password (Basic) or token (Basic with the token as the password, or, where
     * [acceptsTokenHeader], `token`/`Bearer`) signs in as.
     */
    private fun signedIn(request: Request, acceptsTokenHeader: Boolean = true): Account? {
        val header = request.header("Authorization") ?: return null
        Regex("^(?:token|bearer)\\s+(\\S+)$", RegexOption.IGNORE_CASE).find(header)?.let { match ->
            return if (acceptsTokenHeader) tokens[match.groupValues[1]]?.let { accounts[it] } else null
        }
        return accounts.values.firstOrNull { account ->
            account.password != null && header == WacsCredential.Basic(account.login, account.password).authorizationHeader()
        } ?: tokens.entries.firstOrNull { (token, login) -> header == WacsCredential.Basic(login, token).authorizationHeader() }
            ?.let { accounts[it.value] }
    }

    private fun repoInfo(owner: Account, name: String, fork: Boolean, empty: Boolean) = ForgejoRepo(
        id = nextId++,
        name = name,
        fullName = "${owner.login}/$name",
        cloneUrl = cloneUrl(owner.login, name),
        htmlUrl = "$host${owner.login}/$name",
        defaultBranch = BRANCH,
        owner = ForgejoOwner(owner.id, owner.login),
        fork = fork,
        empty = empty,
    )

    /** One page of [items], by the request's `page` and `limit`, as Forgejo pages its listings. */
    private fun <T> Request.respondPage(items: List<T>, serializer: kotlinx.serialization.KSerializer<T>): Response {
        val page = url().queryParameter("page")?.toInt() ?: 1
        val limit = url().queryParameter("limit")?.toInt() ?: 30
        val slice = items.drop((page - 1) * limit).take(limit)
        return respond(200, json.encodeToString(ListSerializer(serializer), slice))
    }

    private fun Request.bodyBytes(): ByteArray = Buffer().also { body()?.writeTo(it) }.readByteArray()

    private fun Request.bodyText(): String = bodyBytes().decodeToString()

    private fun Request.respond(code: Int, body: String = "", type: String = "application/json"): Response =
        Response.Builder().request(this).protocol(Protocol.HTTP_1_1).code(code).message("fake")
            .body(ResponseBody.create(MediaType.parse(type), body)).build()

    private fun Request.respondBytes(bytes: ByteArray): Response =
        Response.Builder().request(this).protocol(Protocol.HTTP_1_1).code(200).message("fake")
            .body(ResponseBody.create(MediaType.parse("application/octet-stream"), bytes)).build()

    companion object {
        const val HOST = "wacs.test"
        const val PIPELINE_HOST = "pipeline.test"
        const val ORG = "AudioTranslation"
        const val BRANCH = "master"
        const val PRESIGNED_HEADER = "X-Presigned-Signature"
        private const val LFS_JSON = "application/vnd.git-lfs+json"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
