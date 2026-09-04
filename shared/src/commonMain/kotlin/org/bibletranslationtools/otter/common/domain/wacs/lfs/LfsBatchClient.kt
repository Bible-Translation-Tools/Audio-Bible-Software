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
package org.bibletranslationtools.otter.common.domain.wacs.lfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * A hand-rolled git-LFS [Batch API](https://github.com/git-lfs/git-lfs/blob/main/docs/api/batch.md)
 * client over OkHttp — used instead of the `git-lfs` binary so the app needs no external tools and
 * behaves identically on Android and Desktop. Transfer shapes here are verified end-to-end against
 * the WACS prototype (create/commit/upload/verify/download round-trip, Spike B + M0 push probe).
 *
 * The client always follows the `href` + `header` the server returns for each object: with
 * `SERVE_DIRECT=false` that routes back through Forgejo (add our credential); with
 * `SERVE_DIRECT=true` it is a presigned object-store URL carrying its own auth (send only the
 * server-supplied headers). Download integrity is enforced: bytes must hash to the pointer `oid`.
 *
 * Not yet implemented (hardening, tracked in the plan §8): HTTP range-resume on download and
 * chunked/resumable PUT on upload. Prefer one object (chapter) per call so a dropped link is cheap.
 */
class LfsBatchClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    /**
     * Download the object named by [pointer] into [dest], verifying `sha256(bytes) == pointer.oid`.
     * [lfsBaseUrl] is the repo's LFS base, i.e. `<clone-url>/info/lfs` (see [batchUrl]).
     */
    suspend fun download(
        lfsBaseUrl: String,
        credential: WacsCredential,
        pointer: LfsPointer,
        dest: File,
    ) = withContext(Dispatchers.IO) {
        val obj = requestBatch(lfsBaseUrl, credential, Operation.DOWNLOAD, pointer)
        obj.error?.let { error("LFS download refused for ${pointer.oid}: ${it.code} ${it.message}") }
        val action = obj.actions?.download
            ?: error("LFS batch returned no download action for ${pointer.oid}")

        dest.parentFile?.mkdirs()
        val batchHost = HttpUrl.parse(batchUrl(lfsBaseUrl))
        val request = transferRequest(action, credential, batchHost).get().build()
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("LFS download HTTP ${resp.code()} for ${pointer.oid}")
            val body = resp.body() ?: error("LFS download: empty body for ${pointer.oid}")
            DigestInputStream(body.byteStream(), digest).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
        }
        val actual = digest.digest().toHex()
        if (actual != pointer.oid || dest.length() != pointer.size) {
            dest.delete()
            error("LFS integrity check failed for ${pointer.oid}: got sha256=$actual size=${dest.length()}")
        }
    }

    /**
     * Upload [source] (whose sha256/size must equal [pointer]) as the object [pointer]. No-ops if the
     * server reports the object already present. Verifies via the server's `verify` action if given.
     */
    suspend fun upload(
        lfsBaseUrl: String,
        credential: WacsCredential,
        pointer: LfsPointer,
        source: File,
    ) = withContext(Dispatchers.IO) {
        val obj = requestBatch(lfsBaseUrl, credential, Operation.UPLOAD, pointer)
        obj.error?.let { error("LFS upload refused for ${pointer.oid}: ${it.code} ${it.message}") }
        val action = obj.actions?.upload ?: return@withContext // already present server-side

        val batchHost = HttpUrl.parse(batchUrl(lfsBaseUrl))
        val putBody = RequestBody.create(OCTET_STREAM, source)
        val put = transferRequest(action, credential, batchHost).put(putBody).build()
        http.newCall(put).execute().use { resp ->
            if (!resp.isSuccessful) error("LFS upload PUT HTTP ${resp.code()} for ${pointer.oid}")
        }
        obj.actions.verify?.let { verify ->
            val body = RequestBody.create(LFS_JSON, json.encodeToString(ObjectRef(pointer.oid, pointer.size)))
            val req = transferRequest(verify, credential, batchHost)
                .post(body).header("Accept", LFS_CONTENT_TYPE).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("LFS verify HTTP ${resp.code()} for ${pointer.oid}")
            }
        }
    }

    private fun requestBatch(
        lfsBaseUrl: String,
        credential: WacsCredential,
        op: Operation,
        pointer: LfsPointer,
    ): BatchObject {
        val payload = json.encodeToString(
            BatchRequest(op.wire, listOf("basic"), listOf(ObjectRef(pointer.oid, pointer.size)))
        )
        val request = Request.Builder()
            .url(batchUrl(lfsBaseUrl))
            .header("Accept", LFS_CONTENT_TYPE)
            .header("Authorization", credential.authorizationHeader())
            .post(RequestBody.create(LFS_JSON, payload))
            .build()
        val text = http.newCall(request).execute().use { resp ->
            val s = resp.body()?.string().orEmpty()
            if (!resp.isSuccessful) error("LFS batch HTTP ${resp.code()}: $s")
            s
        }
        return json.decodeFromString<BatchResponse>(text).objects.firstOrNull()
            ?: error("LFS batch response contained no objects")
    }

    /** Build the transfer request, applying server-supplied headers, adding our credential only for a same-origin href with no auth header. */
    private fun transferRequest(action: Action, credential: WacsCredential, batchHost: HttpUrl?): Request.Builder {
        val builder = Request.Builder().url(action.href)
        var hasAuth = false
        action.header?.forEach { (k, v) -> builder.header(k, v); if (k.equals("Authorization", true)) hasAuth = true }
        val href = HttpUrl.parse(action.href)
        val sameOrigin = href != null && batchHost != null &&
            href.host() == batchHost.host() && href.port() == batchHost.port()
        if (!hasAuth && sameOrigin) builder.header("Authorization", credential.authorizationHeader())
        return builder
    }

    private enum class Operation(val wire: String) { DOWNLOAD("download"), UPLOAD("upload") }

    companion object {
        private const val LFS_CONTENT_TYPE = "application/vnd.git-lfs+json"
        private val LFS_JSON: MediaType? = MediaType.parse(LFS_CONTENT_TYPE)
        private val OCTET_STREAM: MediaType? = MediaType.parse("application/octet-stream")

        /** The batch endpoint for an LFS base url `<clone-url>/info/lfs`. */
        fun batchUrl(lfsBaseUrl: String): String = "${lfsBaseUrl.trimEnd('/')}/objects/batch"

        private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

// ── Batch API wire model ────────────────────────────────────────────────────────────────
@Serializable
private data class BatchRequest(
    val operation: String,
    val transfers: List<String>,
    val objects: List<ObjectRef>,
)

@Serializable
private data class ObjectRef(val oid: String, val size: Long)

@Serializable
private data class BatchResponse(
    val transfer: String? = null,
    val objects: List<BatchObject> = emptyList(),
)

@Serializable
private data class BatchObject(
    val oid: String,
    val size: Long,
    val authenticated: Boolean? = null,
    val actions: Actions? = null,
    val error: BatchError? = null,
)

@Serializable
private data class Actions(
    val download: Action? = null,
    val upload: Action? = null,
    val verify: Action? = null,
)

@Serializable
private data class Action(
    val href: String,
    val header: Map<String, String>? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
private data class BatchError(val code: Int, val message: String)
