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
)
