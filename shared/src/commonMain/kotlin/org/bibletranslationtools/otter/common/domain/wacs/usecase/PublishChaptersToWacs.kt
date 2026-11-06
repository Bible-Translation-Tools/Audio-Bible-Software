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
import kotlinx.coroutines.delay
import kotlinx.coroutines.rx2.await
import kotlinx.serialization.json.Json
import org.bibletranslationtools.kotlinscripturealignment.model.BurritoAudioAlignment
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.EncodeAudioToFlac
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.audio.metadata.BurritoAlignmentMetadata
import org.bibletranslationtools.otter.common.domain.content.FileNamer
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.api.CreatePullRequestRequest
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoApi
import org.bibletranslationtools.otter.common.domain.wacs.api.PipelineError
import org.bibletranslationtools.otter.common.domain.wacs.api.PrepareRequest
import org.bibletranslationtools.otter.common.domain.wacs.api.PrepareResponse
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.bibletranslationtools.otter.common.domain.wacs.lfs.Sha256
import org.slf4j.LoggerFactory
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Publishes recorded chapters as a pull request to the official WACS repo for the workbook's
 * language and translation, following the publish pipeline's contract (its `PUBLISH_API.md` and
 * `CLIENT_INTEGRATION_GUIDE.md` §3 and §5; `scripts/publish-chapters.sh` there is the reference):
 *
 * 1. `prepare`: the pipeline makes sure the official repo (`AudioTranslation/<language>_<translation>`)
 *    and the user's fork exist, and returns both. Retried on 5xx and network failures.
 * 2. Clone the fork (LFS pointers only) and fetch the official repo as `upstream`.
 * 3. A chapter already in one of the user's open pull requests goes back onto that pull request's
 *    branch, replacing its old take; the rest go on a new `publish/<UTC timestamp>` branch.
 * 4. Each affected branch is rebuilt from the official default branch: one commit per chapter,
 *    holding only `media/<book>/<name>.flac` (an LFS pointer) and `<name>.json` (its timing),
 *    where `<name>` is the chapter's [FileNamer.chapterFileName] (`en_ulb_mrk_c01`), with the
 *    message `<BOOK> <CH>` and the trailer `Audio-Chapter: <BOOK> <CH>`. The take is always
 *    encoded to FLAC, and a chapter's earlier `.wav` is removed. The audio is uploaded through the
 *    LFS Batch API before anything that points at it is pushed.
 * 5. Push with a lease, so a push from another device isn't overwritten.
 * 6. Open a pull request for a new branch; an existing one updates in place.
 *
 * The pull request is then checked by the pipeline and merged by a person, so this is "submitted",
 * not "published". Not yet done: the `-detached` fallback for when `prepare` can't be reached (this
 * throws [WacsPublishException.Reason.PIPELINE_UNAVAILABLE] instead), and reading the check's result.
 *
 * Every failure is a [WacsPublishException]; no JGit, Retrofit or I/O exception crosses out of here.
 */
class PublishChaptersToWacs(
    private val apiFactory: WacsApiFactory,
    private val gitClient: IWacsGitClient,
    private val lfsClient: LfsBatchClient,
    private val session: WacsSession,
    private val config: WacsConfig,
    private val tempFileProvider: ITempFileProvider,
    private val encodeToFlac: EncodeAudioToFlac,
    /** Waits between `prepare` attempts; the pipeline's guide asks for 2 s, 8 s, then 30 s. */
    private val retryDelaysMs: List<Long> = listOf(2_000, 8_000, 30_000),
) {
    data class Request(val workbook: Workbook, val chapters: List<Int>)

    data class Result(
        /** The official repo, `owner/name`. */
        val official: String,
        /** The user's fork the chapters were pushed to, `owner/name`. */
        val fork: String,
        /** The pull requests opened or updated; empty if every chapter was already on the official repo. */
        val pullRequests: List<PullRequest>,
    )

    data class PullRequest(
        val number: Long,
        val htmlUrl: String,
        /** Every chapter it carries, as `MRK 1`, including ones from earlier publishes. */
        val chapters: List<String>,
        /** False when an open pull request was updated rather than opened. */
        val opened: Boolean,
    )

    /** Publishing stages, for a progress display. */
    sealed interface Progress {
        data object PreparingRepos : Progress
        data object Cloning : Progress
        data class PreparingChapter(val chapter: Int) : Progress
        data class UploadingAudio(val chapter: Int, val totalBytes: Long) : Progress
        data object Pushing : Progress
        data object OpeningPullRequest : Progress
        data object Done : Progress
    }

    /** A chapter's selected take, and the namer that names the chapter's published files. */
    private data class ChapterTake(val file: File, val fileNamer: FileNamer)

    /** An open pull request from the user's fork, with the chapters its commits carry, in order. */
    private data class OpenPullRequest(val number: Long, val htmlUrl: String, val branch: String, val chapters: List<String>)

    private val json = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(PublishChaptersToWacs::class.java)

    suspend fun publish(request: Request, onProgress: (Progress) -> Unit = {}): Result = try {
        publishChapters(request, onProgress)
    } catch (e: WacsPublishException) {
        logger.warn(
            "Publishing {} {} to WACS failed: {}{}",
            WacsRepoLayout.bookSlug(request.workbook), request.chapters, e.reason, e.detail?.let { " ($it)" }.orEmpty(), e,
        )
        throw e
    }

    private suspend fun publishChapters(request: Request, onProgress: (Progress) -> Unit): Result {
        val credential = session.credential
            ?: throw WacsPublishException(WacsPublishException.Reason.NOT_AUTHENTICATED)
        val api = apiFactory.create(session.host ?: config.baseUrl)
        val auth = credential.authorizationHeader()
        val workbook = request.workbook
        val book = WacsRepoLayout.bookSlug(workbook)
        logger.info("Publishing {} {} to {} as {}", book, request.chapters, session.host ?: config.baseUrl, credential.username)

        // Every chapter needs a finished take before anything is sent.
        val takes = selectedTakes(workbook, request.chapters)

        onProgress(Progress.PreparingRepos)
        val prepared = prepare(
            credential,
            PrepareRequest(language = workbook.target.language.slug, translation = workbook.target.resourceMetadata.identifier),
        )
        val email = wacsCall { api.whoAmI(auth) }.email ?: "${credential.username}@users.noreply.wacs"
        val base = prepared.official.defaultBranch
        val upstreamRef = "refs/remotes/$UPSTREAM/$base"

        val workDir = File(tempFileProvider.tempDirectory, "wacs-publish-${UUID.randomUUID()}")
        val stagingDir = File(tempFileProvider.tempDirectory, "wacs-stage-${UUID.randomUUID()}")
        try {
            onProgress(Progress.Cloning)
            val upstreamTip = gitCall {
                gitClient.clone(prepared.fork.cloneUrl, workDir, credential, base)
                gitClient.fetchRemote(workDir, UPSTREAM, prepared.official.cloneUrl, base, credential)
            }
            logger.info("Cloned {}; {}/{} is at {}", prepared.fork.fullName, UPSTREAM, base, upstreamTip)

            // The replace rule: a chapter already in one of the user's open pull requests goes back
            // onto that pull request's branch, so a chapter is never in two of them.
            val newTakes = takes.mapKeys { (chapter, _) -> WacsRepoLayout.chapterId(book, chapter) }
            val openPulls = openPullRequests(api, auth, prepared)
            val affected = openPulls.filter { pull -> pull.chapters.any { it in newTakes } }
            val onNewBranch = newTakes.keys.filter { id -> affected.none { id in it.chapters } }
            logger.info(
                "Open pull requests from {}: {}; replacing takes in {}; new branch for {}",
                prepared.fork.fullName,
                openPulls.map { "#${it.number} ${it.branch} ${it.chapters}" },
                affected.map { "#${it.number}" },
                onNewBranch,
            )

            val pullRequests = mutableListOf<PullRequest>()
            for (pull in affected) {
                val previousTip = gitCall { gitClient.resolve(workDir, "refs/remotes/origin/${pull.branch}") }
                    ?: throw WacsPublishException(WacsPublishException.Reason.CONFLICT)
                val pushed = rebuild(
                    workDir, stagingDir, prepared, credential, email, pull.branch, previousTip,
                    pull.chapters, newTakes, upstreamRef, upstreamTip, onProgress,
                )
                if (pushed) {
                    logger.info("Updated pull request #{} in place: {}", pull.number, pull.htmlUrl)
                    pullRequests += PullRequest(pull.number, pull.htmlUrl, pull.chapters, opened = false)
                }
            }
            if (onNewBranch.isNotEmpty()) {
                val branch = newBranchName(workDir)
                val pushed = rebuild(
                    workDir, stagingDir, prepared, credential, email, branch, null,
                    onNewBranch, newTakes, upstreamRef, upstreamTip, onProgress,
                )
                if (pushed) {
                    onProgress(Progress.OpeningPullRequest)
                    val opened = wacsCall {
                        api.createPullRequest(
                            prepared.official.owner,
                            prepared.official.name,
                            auth,
                            CreatePullRequestRequest(
                                head = "${prepared.fork.owner}:$branch",
                                base = base,
                                title = pullRequestTitle(onNewBranch),
                                body = onNewBranch.joinToString("\n") { "- $it" },
                            ),
                        )
                    }
                    logger.info("Opened pull request #{} for {}: {}", opened.number, onNewBranch, opened.htmlUrl)
                    pullRequests += PullRequest(opened.number, opened.htmlUrl, onNewBranch, opened = true)
                }
            }

            onProgress(Progress.Done)
            return Result(prepared.official.fullName, prepared.fork.fullName, pullRequests)
        } finally {
            runCatching { stagingDir.deleteRecursively() }
            runCatching { workDir.deleteRecursively() }
        }
    }

    /**
     * `checkout -B [branch] upstream/<base>`, then one commit per chapter in [chapters]: the new take
     * for a chapter in [newTakes], the files as [previousTip] has them for the rest. Pushes with
     * [previousTip] as the lease and returns true, or returns false if every chapter is already on
     * the official repo as is.
     */
    private suspend fun rebuild(
        workDir: File,
        stagingDir: File,
        prepared: PrepareResponse,
        credential: WacsCredential,
        email: String,
        branch: String,
        previousTip: String?,
        chapters: List<String>,
        newTakes: Map<String, ChapterTake>,
        upstreamRef: String,
        upstreamTip: String,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        logger.info("Rebuilding {} from {} with {}", branch, upstreamRef, chapters)
        gitCall { gitClient.startBranch(workDir, branch, upstreamRef) }
        for (id in chapters) {
            val (book, chapter) = parseChapterId(id)
            val present = gitCall { gitClient.listFiles(workDir, "HEAD", WacsRepoLayout.bookDirectory(book)) }
                .filter { WacsRepoLayout.isChapterFile(it, book, chapter) }
            val take = newTakes[id]
            if (take != null) {
                onProgress(Progress.PreparingChapter(chapter))
                val written = writeChapter(workDir, stagingDir, book, chapter, take, prepared, credential, onProgress)
                gitCall { gitClient.stage(workDir, add = written, remove = present - written.toSet()) }
            } else {
                val previous = gitCall { gitClient.listFiles(workDir, previousTip!!, WacsRepoLayout.bookDirectory(book)) }
                    .filter { WacsRepoLayout.isChapterFile(it, book, chapter) }
                gitCall {
                    gitClient.stage(workDir, add = emptyList(), remove = present - previous.toSet())
                    gitClient.restoreFiles(workDir, previousTip!!, previous)
                }
            }
            val commit = gitCall {
                gitClient.commitStaged(workDir, WacsRepoLayout.chapterCommitMessage(book, chapter), credential.username, email)
            }
            logger.info(
                "{} ({}): {}", id, if (take != null) "new take" else "carried over",
                commit?.let { "committed $it" } ?: "same as upstream, no commit",
            )
        }
        if (gitCall { gitClient.resolve(workDir, "HEAD") } == upstreamTip) {
            logger.info("{}: every chapter is already on {}; nothing to push", branch, upstreamRef)
            return false
        }
        onProgress(Progress.Pushing)
        logger.info("Pushing {} (expecting origin at {})", branch, previousTip ?: "nothing: a new branch")
        gitCall { gitClient.push(workDir, credential, branch, previousTip) }
        return true
    }

    /**
     * Encodes [take] to FLAC, writes its LFS pointer and timing file into the working tree, uploads
     * the FLAC to the fork's LFS store, and returns the two repo paths.
     */
    private suspend fun writeChapter(
        workDir: File,
        stagingDir: File,
        book: String,
        chapter: Int,
        take: ChapterTake,
        prepared: PrepareResponse,
        credential: WacsCredential,
        onProgress: (Progress) -> Unit,
    ): List<String> {
        val audioName = take.fileNamer.chapterFileName(WacsRepoLayout.PUBLISHED_AUDIO_EXTENSION)
        val timingName = take.fileNamer.chapterFileName("json")
        val audioPath = WacsRepoLayout.chapterFilePath(book, audioName)
        val timingPath = WacsRepoLayout.chapterFilePath(book, timingName)

        val chapterDir = File(stagingDir, "$book-$chapter").apply { mkdirs() }
        val audio = File(chapterDir, audioName)
        try {
            encodeToFlac(take.file, audio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPublishException(WacsPublishException.Reason.UNKNOWN, e)
        }

        val pointer = try {
            // The timing comes from the take, which holds the markers; the FLAC holds audio only.
            // The repo is the burrito, so the timing file's docid is the audio's path from the repo
            // root (`media/mrk/en_ulb_mrk_c01.flac`).
            val timing = File(chapterDir, timingName)
            val oratureAudio = OratureAudioFile(take.file)
            BurritoAlignmentMetadata(timing, audio, docid = audioPath)
                .write(oratureAudio.getMarkers(), book.lowercase(), chapter, oratureAudio.totalFrames)
            if (!timing.exists()) {
                // A take with no markers yet still gets a valid, empty timing file.
                BurritoAudioAlignment.create(audio, timing, audioPath)
            }
            timing.copyTo(File(workDir, timingPath).apply { parentFile?.mkdirs() }, overwrite = true)

            // The pointer, never the audio, goes in git.
            val pointer = LfsPointer(Sha256.hex(audio), audio.length())
            File(workDir, audioPath).apply { parentFile?.mkdirs() }.writeText(pointer.serialize())
            pointer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPublishException(WacsPublishException.Reason.UNKNOWN, e)
        }

        onProgress(Progress.UploadingAudio(chapter, audio.length()))
        logger.info("{} {}: uploading {} bytes to {} (oid {})", book, chapter, audio.length(), prepared.fork.fullName, pointer.oid)
        try {
            lfsClient.upload("${prepared.fork.cloneUrl.trimEnd('/')}/info/lfs", credential, pointer, audio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classifyLfsFailure(e)
        }
        return listOf(audioPath, timingPath)
    }

    /** The selected take of each chapter in [chapters], which must all have one, and its namer. */
    private suspend fun selectedTakes(workbook: Workbook, chapters: List<Int>): Map<Int, ChapterTake> {
        val all = try {
            workbook.target.chapters.toList().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPublishException(WacsPublishException.Reason.UNKNOWN, e)
        }
        return chapters.associateWith { sort ->
            val chapter = all.find { it.sort == sort }
            val file = chapter?.getSelectedTake()?.file?.takeIf { it.exists() }
                ?: throw WacsPublishException(WacsPublishException.Reason.NO_AUDIO)
            val namer = try {
                // Named as the chapter's own takes are, so its files match the project's.
                WorkbookFileNamerBuilder.createFileNamer(workbook, chapter, null, chapter, workbook.sourceMetadataSlug)
            } catch (e: Exception) {
                throw WacsPublishException(WacsPublishException.Reason.UNKNOWN, e)
            }
            ChapterTake(file, namer)
        }
    }

    /** `prepare`, retried with [retryDelaysMs] on 5xx and network failures; a 4xx is final. */
    private suspend fun prepare(credential: WacsCredential, request: PrepareRequest): PrepareResponse {
        val pipeline = apiFactory.createPipeline(session.pipelineUrl ?: config.pipelineUrl)
        var lastFailure: Exception? = null
        for (attempt in 0..retryDelaysMs.size) {
            if (attempt > 0) delay(retryDelaysMs[attempt - 1])
            try {
                return pipeline.prepare(credential.authorizationHeader(), request).also {
                    logger.info(
                        "Pipeline prepared {} (created: {}) and fork {} (created: {})",
                        it.official.fullName, it.official.created, it.fork.fullName, it.fork.created,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() in 400..499) throw pipelineRefusal(e)
                logger.warn("prepare attempt {} of {} failed: HTTP {}", attempt + 1, retryDelaysMs.size + 1, e.code())
                lastFailure = e
            } catch (e: IOException) {
                logger.warn("prepare attempt {} of {} failed: {}", attempt + 1, retryDelaysMs.size + 1, e.toString())
                lastFailure = e
            }
        }
        throw WacsPublishException(WacsPublishException.Reason.PIPELINE_UNAVAILABLE, lastFailure)
    }

    private fun pipelineRefusal(e: HttpException): WacsPublishException {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val error = body?.let { runCatching { json.decodeFromString(PipelineError.serializer(), it) }.getOrNull() }
        logger.warn("The pipeline refused to prepare the repos: HTTP {} {}: {}", e.code(), error?.error, error?.message ?: body)
        return when (e.code()) {
            401, 403 -> WacsPublishException(WacsPublishException.Reason.AUTH_REJECTED, e)
            else -> WacsPublishException(
                WacsPublishException.Reason.PIPELINE_REJECTED, e, detail = error?.message?.ifBlank { null } ?: error?.error,
            )
        }
    }

    private suspend fun openPullRequests(api: ForgejoApi, auth: String, prepared: PrepareResponse): List<OpenPullRequest> {
        val (owner, name) = prepared.official.owner to prepared.official.name
        val pulls = pages { page -> wacsCall { api.listPullRequests(owner, name, auth, state = "open", page = page, limit = PAGE_SIZE) } }
            .filter { it.head?.repo?.fullName.equals(prepared.fork.fullName, ignoreCase = true) && it.head?.ref != null }
            .sortedBy { it.number }
        return pulls.map { pull ->
            val commits = pages { page -> wacsCall { api.listPullRequestCommits(owner, name, pull.number, auth, page = page, limit = PAGE_SIZE) } }
            val chapters = commits.flatMap { WacsRepoLayout.chaptersInCommitMessage(it.commit.message) }.distinct()
            OpenPullRequest(pull.number, pull.htmlUrl, pull.head!!.ref!!, chapters)
        }
    }

    private suspend fun newBranchName(workDir: File): String {
        var branch = "publish/" + ZonedDateTime.now(ZoneOffset.UTC).format(BRANCH_TIME)
        while (gitCall { gitClient.resolve(workDir, "refs/remotes/origin/$branch") } != null) branch += "-x"
        return branch
    }

    /** `MRK 1–3, 5; LUK 2`: each book's chapters, consecutive ones as a range. */
    private fun pullRequestTitle(chapters: List<String>): String =
        chapters.map(::parseChapterId).groupBy({ it.first }, { it.second }).entries.joinToString("; ") { (book, numbers) ->
            val runs = mutableListOf<IntRange>()
            numbers.sorted().forEach { n ->
                val last = runs.lastOrNull()
                if (last != null && last.last + 1 == n) runs[runs.lastIndex] = last.first..n else runs += n..n
            }
            "$book " + runs.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
        }

    private fun parseChapterId(id: String): Pair<String, Int> {
        val (book, number) = id.trim().split(Regex("\\s+")).let { it.first() to it.last().toIntOrNull() }
        return book to (number ?: throw WacsPublishException(WacsPublishException.Reason.UNKNOWN, IllegalStateException("Bad chapter $id")))
    }

    private suspend fun <T> pages(fetch: suspend (Int) -> List<T>): List<T> {
        val all = mutableListOf<T>()
        var page = 1
        while (true) {
            val batch = fetch(page)
            all += batch
            if (batch.size < PAGE_SIZE) return all
            page++
        }
    }

    /** A WACS REST call, with its failure classified. */
    private suspend fun <T> wacsCall(call: suspend () -> T): T = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: WacsPublishException) {
        throw e
    } catch (e: HttpException) {
        val request = e.response()?.raw()?.request()
        logger.warn("WACS answered HTTP {} to {} {}", e.code(), request?.method(), request?.url()?.let { it.host() + it.encodedPath() })
        throw classifyHttp(e)
    } catch (e: IOException) {
        throw WacsPublishException(WacsPublishException.Reason.NETWORK, e)
    } catch (e: Exception) {
        throw WacsPublishException(WacsPublishException.Reason.UNKNOWN, e)
    }

    /** A git operation, with its failure classified. */
    private suspend fun <T> gitCall(call: suspend () -> T): T = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: WacsPublishException) {
        throw e
    } catch (e: Exception) {
        throw classifyGitFailure(e)
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

    private companion object {
        const val UPSTREAM = "upstream"
        const val PAGE_SIZE = 50
        val BRANCH_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
