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

import io.github.vinceglb.filekit.PlatformFile
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.shared.domain.SourceAudioImporter
import java.io.File

/**
 * M3: the bridge from a [PullChapter]-materialized WACS chapter to the app's EXISTING "source
 * audio" machinery, rather than a new WACS-specific source concept.
 *
 * **How "source" is modeled today (found while designing M3):** source audio is per-chapter loose
 * files (`.wav`/`.mp3`) named `<bookSlug>_c<NN>.<ext>`, dropped into a project's
 * `.apps/orature/source/audio/` directory (see [org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants.SOURCE_AUDIO_DIR])
 * and located per-project via [org.bibletranslationtools.otter.common.api.persistence.IProjectDirectories.getProjectSourceAudioDirectory].
 * [SourceAudioImporter.importForWorkbook] already implements exactly this for a user-picked file
 * (see its "loose audio" path); it requires an existing [WorkbookDescriptor] (a project whose source
 * language/book identify where the file goes) — nothing pulled from WACS creates a project on its
 * own.
 *
 * **Why this path instead of [org.bibletranslationtools.otter.common.domain.project.importer.BurritoImporter]:**
 * the plan doc flagged Burrito-conversion as the likely bridge, since a WACS repo IS a Scripture
 * Burrito. On inspection, [org.bibletranslationtools.otter.common.domain.project.importer.BurritoImporter]
 * expects a whole burrito **archive** (a zip) and round-trips it through a full RC conversion +
 * import — an all-or-nothing shape that fights this milestone's "materialize just the chapters the
 * user opens" requirement (repackaging one chapter as a fake single-chapter burrito zip just to
 * satisfy that pipeline would be more invasive, not less). [SourceAudioImporter]'s loose-audio path
 * already accepts one chapter file at a time and is the one already-tested per-chapter entry point
 * into the source-audio store, so it is reused as-is (this class is the only new code — it renames
 * the pulled file to the convention [SourceAudioImporter] expects, then hands it in exactly like a
 * user-picked file). **Flagged for review:** which local project a pulled chapter should attach to
 * is a UX/product decision this milestone does not make — see [import]'s KDoc.
 */
class ImportPulledChapterAsSource(
    private val sourceAudioImporter: SourceAudioImporter,
) {
    /**
     * Renames [pulledAudio] (as materialized by [PullChapter]) to the `<bookSlug>_c<NN>.<ext>`
     * convention [SourceAudioImporter] expects, then imports it into [descriptor]'s source-audio
     * directory exactly as if the user had picked this file in the existing "Import Source Audio"
     * flow ([org.bibletranslationtools.bttrecorder2.ui.components.ProjectInfoDialog]).
     *
     * **Caller's responsibility, deliberately left open (product decision, not guessed here):**
     * [descriptor] must already exist and its book should match [bookSlug] for the audio to land
     * anywhere useful — this class does not create, search for, or validate a matching project. The
     * M3 repo-picker UI lists the user's existing projects and lets them pick one; whether pulling
     * should instead be able to CREATE a new project from a WACS source (auto-deriving source
     * language/edition/book from the repo) is exactly the open UX question to raise with the product
     * owner before building more UI around it.
     */
    suspend fun import(
        descriptor: WorkbookDescriptor,
        bookSlug: String,
        chapterNumber: Int,
        pulledAudio: File,
    ): SourceAudioImporter.Result {
        val ext = pulledAudio.extension.ifBlank { "wav" }
        val named = File(
            pulledAudio.parentFile,
            SourceAudioImporter.formatChapterFileName(bookSlug, chapterNumber, ext),
        )
        if (named != pulledAudio) {
            pulledAudio.copyTo(named, overwrite = true)
        }
        return sourceAudioImporter.importForWorkbook(descriptor, listOf(PlatformFile(named)))
    }
}
