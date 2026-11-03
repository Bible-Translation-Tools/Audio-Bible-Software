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
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * The audio publishing pipeline's Publish API: the one call made before every upload. It creates
 * the official repo (`AudioTranslation/<language>_<translation>`) if it's missing, forks it into
 * the user's account if they have no fork, and returns both. Everything after that (push, pull
 * request) goes to WACS directly. Spec: the pipeline's `PUBLISH_API.md` and `openapi.yaml`.
 *
 * Authorization is the user's WACS token (`token <token>`); the pipeline doesn't accept a password.
 * A failure is an HTTP error whose body is a [PipelineError].
 */
interface PublishPipelineApi {

    @POST("v1/publish/prepare")
    suspend fun prepare(
        @Header("Authorization") auth: String,
        @Body request: PrepareRequest,
    ): PrepareResponse
}

@Serializable
data class PrepareRequest(
    /** IETF tag, e.g. `fr` or `es-419`. */
    val language: String,
    /** Translation code, e.g. `ulb`; no `_`. */
    val translation: String,
    /** Editions after the first add `_<N>e` to the repo name. */
    val edition: Int = 1,
)

@Serializable
data class PrepareResponse(val official: PreparedOfficialRepo, val fork: PreparedFork)

@Serializable
data class PreparedOfficialRepo(
    val owner: String,
    val name: String,
    @SerialName("clone_url") val cloneUrl: String,
    @SerialName("default_branch") val defaultBranch: String,
    val created: Boolean = false,
) {
    val fullName: String get() = "$owner/$name"
}

/** The user's fork. Use its [name] as given: an older fork may not be named `<official>_audio`. */
@Serializable
data class PreparedFork(
    val owner: String,
    val name: String,
    @SerialName("clone_url") val cloneUrl: String,
    val created: Boolean = false,
) {
    val fullName: String get() = "$owner/$name"
}

/** Every pipeline error body. Branch on [error], which is stable; [message] is for people. */
@Serializable
data class PipelineError(val error: String, val message: String = "")
