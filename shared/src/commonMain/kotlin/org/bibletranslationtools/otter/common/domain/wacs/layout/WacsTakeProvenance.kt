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
package org.bibletranslationtools.otter.common.domain.wacs.layout

import java.io.File

/**
 * M3.1: records which LFS `oid` (sha256) a restored take was materialized from, so a later restore
 * of the same repo/chapter can tell "already have this exact chapter" from "this is new" **before**
 * downloading anything (the oid is known from the pointer; see
 * [org.bibletranslationtools.otter.common.domain.wacs.usecase.RestoreChapterFromWacs]).
 *
 * **Why not `take_entity.checksum`, which the M3.1 design doc originally proposed for this:**
 * investigating the take model (see that class's KDoc) found `checksum` is not a free general-
 * purpose take-identity field — it is [org.bibletranslationtools.otter.common.utils.computeFileChecksum]'s
 * **MD5**, populated only when a take's *checking status* is recorded
 * ([org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState]), and compared against a
 * fresh MD5 of the take's current file elsewhere ([org.bibletranslationtools.otter.common.data.workbook.Chunk],
 * `ChapterTranslationBuilder`) to decide whether a checked take's audio has since changed. Writing
 * the sha256 LFS `oid` into that column would silently corrupt that unrelated feature (a restored
 * take would misreport as having *saved-checksum* state despite never having been checked) while
 * still not being directly comparable to anything (`Take.checksum()` always computes MD5, so it
 * would never equal the sha256 stored there). Provenance therefore lives in its own file instead of
 * a DB column — no schema migration, and the checking-status feature is left alone.
 *
 * A plain sidecar file next to the take's audio, one directory level below where NARRATION mode
 * already keeps a non-audio companion (`active_verses.json` beside `chapter_narration.pcm`, see
 * [org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants]) — so a bare
 * "metadata file living next to a take's audio in the project's own take directory" is not a new
 * idea in this codebase, just applied to a single take file instead of a whole chapter's scratch
 * audio.
 */
object WacsTakeProvenance {
    private const val SUFFIX = ".wacs-oid"

    /** The sidecar file for [takeFile] — same directory, name plus [SUFFIX]; never itself the audio. */
    fun sidecarFile(takeFile: File): File = File(takeFile.parentFile, takeFile.name + SUFFIX)

    /** Records that [takeFile]'s audio came from LFS object [oid] (lowercase-hex sha256). */
    fun write(takeFile: File, oid: String) {
        sidecarFile(takeFile).writeText(oid)
    }

    /** The oid [takeFile] was restored from, or null if it wasn't (or the sidecar is unreadable). */
    fun read(takeFile: File): String? =
        sidecarFile(takeFile)
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim() }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
}
