package org.bibletranslationtools.otter.common.domain.wacs.fake

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoOwner
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoPrBranchInfo
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoPullRequest
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoUser
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.git.GitConfigIsolation
import org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.git.JGitWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-process stand-in for a WACS host (Forgejo with git-LFS), so WACS sync is tested without a
 * server: the tests have to pass in CI from this repo alone.
 *
 * - REST and LFS: an OkHttp interceptor on [http] answers what [ForgejoApi][org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoApi]
 *   and [LfsBatchClient] ask, from memory. Nothing is sent over a network; [apiFactory] and
 *   [lfsClient] are built on [http].
 * - git: each repo's remote is a bare repository under [root]. [gitClient] is the real
 *   [JGitWacsGitClient], with a repo's clone URL pointed at its bare repository, so clone, fetch,
 *   commit and push run for real.
 *
 * It models the slice of Forgejo the client uses, with Forgejo's status codes where the client acts
 * on them: 401 for a wrong password, 404 for a missing repo or LFS object, 409 for a repo or fork
 * that already exists or a duplicate pull request, 422 for an upload whose bytes don't match its
 * oid. git itself doesn't check credentials here; the REST calls in front of it do.
 */
class FakeWacs(private val root: File) {

    val host = "http://$HOST/"

    /** Every request the host answered, oldest first. */
    val requests: List<RecordedRequest> get() = recorded.toList()

    /** A request the host answered: its method, its path (no query), and its headers. */
    data class RecordedRequest(val method: String, val host: String, val path: String, val headers: Headers)

    /** When true, every request fails as if the host can't be reached. */
    @Volatile
    var unreachable = false

    /**
     * When set, LFS transfers go to this separate object-store host with a presigned header of their
     * own, as Forgejo does with `SERVE_DIRECT`, rather than back through the WACS host.
     */
    @Volatile
    var objectStoreHost: String? = null

    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()
    val apiFactory = WacsApiFactory(http)
    val lfsClient = LfsBatchClient(http)
    val gitClient: IWacsGitClient

    private data class Account(val id: Long, val login: String, val password: String?)

    private class HostedRepo(val info: ForgejoRepo, val remote: File, val lfs: MutableMap<String, ByteArray>)

    private class HostedPull(val target: String, val pull: ForgejoPullRequest)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val accounts = mutableMapOf<String, Account>()
    private val repos = linkedMapOf<String, HostedRepo>()
    private val pulls = mutableListOf<HostedPull>()
    private val failures = mutableListOf<Triple<String, String, Int>>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()
    private var nextId = 1L

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

    /** A fresh checkout of `owner/name`'s `master`, to look at what was pushed. */
    @Synchronized
    fun checkout(fullName: String): File {
        val dir = File(root, "checkout-${UUID.randomUUID()}")
        Git.cloneRepository().setURI(requireNotNull(repos[fullName]).remote.toURI().toString())
            .setBranch(BRANCH).setDirectory(dir).call().close()
        return dir
    }

    /** The log of `owner/name`'s `master`, newest first, as commit messages. */
    @Synchronized
    fun log(fullName: String): List<String> = checkout(fullName).let { dir ->
        try {
            Git.open(dir).use { git -> git.log().call().map { it.fullMessage } }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Answers [method] [path] (the URL's encoded path) with [code] from now on. */
    @Synchronized
    fun fail(method: String, path: String, code: Int) {
        failures += Triple(method, path, code)
    }

    fun cloneUrl(owner: String, name: String) = "$host$owner/$name.git"

    // ── git ────────────────────────────────────────────────────────────────────────────────

    /** The real JGit client, with a hosted repo's clone URL replaced by its bare repository on disk. */
    private inner class RemotesOnDisk(private val jgit: IWacsGitClient) : IWacsGitClient by jgit {
        override suspend fun clone(remoteUrl: String, dir: File, credential: WacsCredential, branch: String) =
            jgit.clone(remoteFor(remoteUrl), dir, credential, branch)

        override suspend fun syncFromUpstream(dir: File, upstreamUrl: String, credential: WacsCredential, branch: String) =
            jgit.syncFromUpstream(dir, remoteFor(upstreamUrl), credential, branch)
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
        failures.firstOrNull { (method, path) -> method == request.method() && path == url.encodedPath() }
            ?.let { (_, _, code) -> return request.respond(code, """{"message":"injected failure"}""") }

        if (url.host() == objectStoreHost) return objectStore(request)
        check(url.host() == HOST) { "FakeWacs only answers $HOST, not ${url.host()}" }

        val segments = url.pathSegments().filter { it.isNotEmpty() }
        val account = signedIn(request) ?: return request.respond(401, """{"message":"unauthorized"}""")
        return when {
            segments.take(2) == listOf("api", "v1") -> api(request, account, segments.drop(2))
            segments.size >= 5 && segments[1].endsWith(".git") && segments[2] == "info" && segments[3] == "lfs" ->
                lfs(request, "${segments[0]}/${segments[1].removeSuffix(".git")}", segments.drop(4))
            else -> request.respond(404)
        }
    }

    private fun api(request: Request, account: Account, path: List<String>): Response {
        val method = request.method()
        return when {
            path == listOf("user") && method == "GET" ->
                request.respond(200, json.encodeToString(ForgejoUser.serializer(), ForgejoUser(account.id, account.login)))
            path == listOf("user", "repos") && method == "GET" ->
                request.respondRepos(repos.values.filter { it.info.owner.login == account.login })
            path == listOf("user", "repos") && method == "POST" -> createRepo(request, account)
            path.size == 3 && path[0] == "orgs" && path[2] == "repos" && method == "GET" ->
                request.respondRepos(repos.values.filter { it.info.owner.login == path[1] })
            path.size >= 3 && path[0] == "repos" -> {
                val fullName = "${path[1]}/${path[2]}"
                val hosted = repos[fullName] ?: return request.respond(404, """{"message":"repo not found"}""")
                when {
                    path.size == 3 && method == "GET" ->
                        request.respond(200, json.encodeToString(ForgejoRepo.serializer(), hosted.info))
                    path.getOrNull(3) == "forks" && method == "POST" -> fork(request, account, hosted)
                    path.getOrNull(3) == "pulls" && method == "GET" -> {
                        val state = request.url().queryParameter("state") ?: "open"
                        val list = pulls.filter { it.target == fullName && (state == "all" || it.pull.state == state) }.map { it.pull }
                        request.respond(200, json.encodeToString(ListSerializer(ForgejoPullRequest.serializer()), list))
                    }
                    path.getOrNull(3) == "pulls" && method == "POST" -> openPull(request, hosted)
                    else -> request.respond(404)
                }
            }
            else -> request.respond(404)
        }
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

    private fun fork(request: Request, account: Account, parent: HostedRepo): Response {
        val fullName = "${account.login}/${parent.info.name}"
        if (fullName in repos) return request.respond(409, """{"message":"repository is already forked by user"}""")
        val remote = File(root, "remotes/$fullName.git")
        parent.remote.copyRecursively(remote)
        val info = repoInfo(account, parent.info.name, fork = true, empty = false)
        repos[fullName] = HostedRepo(info, remote, parent.lfs.toMutableMap())
        return request.respond(202, json.encodeToString(ForgejoRepo.serializer(), info))
    }

    private fun openPull(request: Request, target: HostedRepo): Response {
        val body = json.decodeFromString(CreatePullRequestRequest.serializer(), request.bodyText())
        val headOwner = body.head.substringBefore(':')
        val headRef = body.head.substringAfter(':')
        val head = repos["$headOwner/${target.info.name}"]
            ?: return request.respond(422, """{"message":"head repository not found"}""")
        val duplicate = pulls.any {
            it.target == target.info.fullName && it.pull.state == "open" &&
                it.pull.head?.repoId == head.info.id && it.pull.head.ref == headRef && it.pull.base?.ref == body.base
        }
        if (duplicate) return request.respond(409, """{"message":"pull request already exists"}""")
        val number = pulls.count { it.target == target.info.fullName } + 1L
        val pull = ForgejoPullRequest(
            number = number,
            htmlUrl = "$host${target.info.fullName}/pulls/$number",
            state = "open",
            head = ForgejoPrBranchInfo(headRef, head.info.id),
            base = ForgejoPrBranchInfo(body.base, target.info.id),
        )
        pulls += HostedPull(target.info.fullName, pull)
        return request.respond(201, json.encodeToString(ForgejoPullRequest.serializer(), pull))
    }

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

    private fun signedIn(request: Request): Account? {
        val header = request.header("Authorization") ?: return null
        return accounts.values.firstOrNull { account ->
            account.password != null &&
                header == WacsCredential.Basic(account.login, account.password).authorizationHeader()
        }
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

    /** One page of [hosted], by the request's `page` and `limit`, as Forgejo pages its listings. */
    private fun Request.respondRepos(hosted: List<HostedRepo>): Response {
        val page = url().queryParameter("page")?.toInt() ?: 1
        val limit = url().queryParameter("limit")?.toInt() ?: 30
        val slice = hosted.map { it.info }.drop((page - 1) * limit).take(limit)
        return respond(200, json.encodeToString(ListSerializer(ForgejoRepo.serializer()), slice))
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
        const val BRANCH = "master"
        const val PRESIGNED_HEADER = "X-Presigned-Signature"
        private const val LFS_JSON = "application/vnd.git-lfs+json"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
