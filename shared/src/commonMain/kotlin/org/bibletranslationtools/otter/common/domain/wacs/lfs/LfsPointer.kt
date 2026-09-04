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

/**
 * A git-LFS pointer — the ~128-byte text blob that stands in git for an LFS-tracked file
 * (see https://github.com/git-lfs/git-lfs/blob/main/docs/spec.md). Canonical form:
 *
 * ```
 * version https://git-lfs.github.com/spec/v1
 * oid sha256:<64 lowercase hex>
 * size <bytes>
 * ```
 *
 * Because the WACS client hand-rolls LFS transfer (no smudge/clean filter installed in JGit — see
 * [org.bibletranslationtools.otter.common.domain.wacs.git.GitConfigIsolation]), this is the content
 * JGit checks out at an LFS path, and it carries the [oid]/[size] we hand to the LFS Batch API.
 */
data class LfsPointer(
    /** The object id: the lowercase-hex SHA-256 of the real file content. */
    val oid: String,
    /** The real file size in bytes. */
    val size: Long,
) {
    /** Serialize to the canonical pointer text (LF line endings, trailing newline). */
    fun serialize(): String = buildString {
        append(VERSION_LINE).append('\n')
        append("oid sha256:").append(oid).append('\n')
        append("size ").append(size).append('\n')
    }

    companion object {
        const val VERSION_LINE = "version https://git-lfs.github.com/spec/v1"

        // A pointer is small; anything larger is real content, not a pointer.
        private const val MAX_POINTER_BYTES = 1024
        private val OID_LINE = Regex("""(?m)^oid sha256:([0-9a-f]{64})$""")
        private val SIZE_LINE = Regex("""(?m)^size (\d+)$""")

        /**
         * Cheap check for whether [bytes] look like an LFS pointer rather than real file content.
         * Guards on size and the version prefix so we never try to UTF-8-decode a large audio blob.
         */
        fun isPointer(bytes: ByteArray): Boolean {
            if (bytes.isEmpty() || bytes.size > MAX_POINTER_BYTES) return false
            val prefix = VERSION_LINE.encodeToByteArray()
            if (bytes.size < prefix.size) return false
            for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
            return true
        }

        /** Parse pointer [text]; returns null if it is not a well-formed v1 pointer. */
        fun parse(text: String): LfsPointer? {
            if (!text.startsWith(VERSION_LINE)) return null
            val oid = OID_LINE.find(text)?.groupValues?.get(1) ?: return null
            val size = SIZE_LINE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            return LfsPointer(oid, size)
        }

        /** Parse pointer [bytes]; returns null if they are not a well-formed v1 pointer. */
        fun parse(bytes: ByteArray): LfsPointer? =
            if (isPointer(bytes)) parse(bytes.decodeToString()) else null
    }
}
