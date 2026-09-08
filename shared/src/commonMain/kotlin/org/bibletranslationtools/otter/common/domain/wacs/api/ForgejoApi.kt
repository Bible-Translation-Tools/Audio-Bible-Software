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
package org.bibletranslationtools.otter.common.domain.wacs.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The slice of the Forgejo (Gitea-compatible) REST API the WACS client needs: identify the user,
 * look repos up, create the user's copy by forking the official `AudioTranslation/<repo>`, and open
 * the contribution PR back to it. git/LFS transport is handled elsewhere (JGit + LfsBatchClient);
 * this is only the control-plane.
 *
 * Auth is per-call via the `Authorization` header (Basic now; Bearer once OAuth lands) — pass
 * [org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential.authorizationHeader]. A
 * 4xx surfaces as a Retrofit `HttpException`; callers handle e.g. 409 (repo/fork already exists).
 */
interface ForgejoApi {

    @GET("api/v1/user")
    suspend fun whoAmI(@Header("Authorization") auth: String): ForgejoUser

    @GET("api/v1/repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Authorization") auth: String,
    ): ForgejoRepo

    @POST("api/v1/user/repos")
    suspend fun createRepo(
        @Header("Authorization") auth: String,
        @Body request: CreateRepoRequest,
    ): ForgejoRepo

    /** Fork `owner/repo` into the caller's account (or an org, if [ForkRepoRequest.organization] set). */
    @POST("api/v1/repos/{owner}/{repo}/forks")
    suspend fun forkRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Authorization") auth: String,
        @Body request: ForkRepoRequest = ForkRepoRequest(),
    ): ForgejoRepo

    /** Open a PR on `owner/repo` (the official repo) from the user's fork. */
    @POST("api/v1/repos/{owner}/{repo}/pulls")
    suspend fun createPullRequest(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Authorization") auth: String,
        @Body request: CreatePullRequestRequest,
    ): ForgejoPullRequest

    /**
     * M4: list `owner/repo`'s pull requests, so a caller can find one already open from a
     * particular fork/branch instead of creating a duplicate (Forgejo/Gitea auto-updates an open
     * PR's diff whenever its head branch gets new commits, which is exactly what re-publishing a
     * later chapter to the same fork branch does). [ForgejoPullRequest.head]/[base] carry enough
     * (`repo_id` + `ref`) to match without a second lookup.
     */
    @GET("api/v1/repos/{owner}/{repo}/pulls")
    suspend fun listPullRequests(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Authorization") auth: String,
        @Query("state") state: String = "open",
    ): List<ForgejoPullRequest>

    /**
     * M3: page through the `AudioTranslation` org's repos for the pull-as-source repo picker —
     * every published-audio repo lives here (see [WacsConfig.org][org.bibletranslationtools.otter.common.domain.wacs.WacsConfig.org]).
     * Callers page with [page]/[limit] until a short page comes back (see
     * [org.bibletranslationtools.otter.common.domain.wacs.usecase.ListWacsRepos]); reuses
     * [ForgejoRepo] rather than a new DTO since the shape is identical to [getRepo].
     */
    @GET("api/v1/orgs/{org}/repos")
    suspend fun listOrgRepos(
        @Path("org") org: String,
        @Header("Authorization") auth: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
    ): List<ForgejoRepo>

    companion object {
        /** WACS bot-blocks unknown clients (403); this header is on the allowlist (see build.gradle.kts). */
        const val WA_TOOL_HEADER = "X-Requested-With"
        const val WA_TOOL_VALUE = "WA-Tool-Orature"

        // Built in the DI (Koin) module — see WacsApiFactory + the `wacsModule` Koin module, NOT
        // here, so the interface stays transport-agnostic. `baseUrl` must end in `/`
        // (e.g. "http://localhost:3000/"); an OkHttp interceptor sets
        // WA_TOOL_HEADER=WA_TOOL_VALUE on every request; auth stays per-call.
        //
        // Converter resolved (M1): retrofit2-kotlinx-serialization-converter 1.0.0's
        // `StringFormat.asConverterFactory(MediaType)` extension IS public — only the `Factory`
        // class it returns (as the public `Converter.Factory` type) is `internal`, which is a
        // legal "return a public supertype" pattern. `json.asConverterFactory(mediaType)` compiles
        // fine from Kotlin as-is; no version bump or Java shim needed (see WacsApiFactory's KDoc).
    }
}

// ── DTOs ────────────────────────────────────────────────────────────────────────────────
@Serializable
data class ForgejoUser(
    val id: Long,
    val login: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

@Serializable
data class ForgejoOwner(val id: Long, val login: String)

@Serializable
data class ForgejoRepo(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("clone_url") val cloneUrl: String,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("default_branch") val defaultBranch: String = "master",
    val owner: ForgejoOwner,
    val fork: Boolean = false,
    val empty: Boolean = false,
)

@Serializable
data class CreateRepoRequest(
    val name: String,
    val description: String = "",
    @SerialName("private") val isPrivate: Boolean = false,
    @SerialName("auto_init") val autoInit: Boolean = true,
    @SerialName("default_branch") val defaultBranch: String = "master",
)

@Serializable
data class ForkRepoRequest(
    val organization: String? = null,
    val name: String? = null,
)

@Serializable
data class CreatePullRequestRequest(
    val head: String,
    val base: String,
    val title: String,
    val body: String = "",
)

@Serializable
data class ForgejoPullRequest(
    val number: Long,
    @SerialName("html_url") val htmlUrl: String,
    val state: String,
    val head: ForgejoPrBranchInfo? = null,
    val base: ForgejoPrBranchInfo? = null,
)

/**
 * The slice of Forgejo's `PRBranchInfo` needed to identify which repo/branch a PR's head or base
 * points at, e.g. to tell "this open PR's head is fork #42's `master` branch" apart from another
 * fork's PR against the same official repo. [repoId] is enough to match a fork ([ForgejoRepo.id])
 * without decoding the (much larger) nested repository object Forgejo also includes.
 */
@Serializable
data class ForgejoPrBranchInfo(
    val ref: String? = null,
    @SerialName("repo_id") val repoId: Long? = null,
)
