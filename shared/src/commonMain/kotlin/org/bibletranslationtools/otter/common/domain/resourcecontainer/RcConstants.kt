/**
 * Copyright (C) 2020-2024 Wycliffe Associates
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
package org.bibletranslationtools.otter.common.domain.resourcecontainer

object RcConstants {
    /**
     * Where a resource container keeps its media — a directory *inside* the container, which for a
     * project backup is where the selected chapter takes go and what the manifest's project path
     * points at.
     *
     * Not to be confused with `composeResources/files/content`, the path on the app's own Compose
     * resource path where bundled source containers ship. The two are unrelated despite both
     * mentioning "content", and conflating them puts an internal app path into a published backup.
     */
    const val MEDIA_DIR = "content"
    const val APP_SPECIFIC_DIR = ".apps/orature"
    const val TAKE_DIR = "$APP_SPECIFIC_DIR/takes"
    const val SOURCE_DIR = "$APP_SPECIFIC_DIR/source"
    const val SOURCE_AUDIO_DIR = "$APP_SPECIFIC_DIR/source/audio"
    const val SELECTED_TAKES_FILE = "$APP_SPECIFIC_DIR/selected.txt"
    const val CHUNKS_FILE = "$APP_SPECIFIC_DIR/chunks.json"
    const val PROJECT_MODE_FILE = "$APP_SPECIFIC_DIR/project_mode.json"
    const val CHECKING_STATUS_FILE = "$APP_SPECIFIC_DIR/checking_status.json"
    const val LICENSE_FILE = "LICENSE.md"
    const val SOURCE_MEDIA_DIR = "media"
    const val CHAPTER_NARRATION_FILE = "$TAKE_DIR/%s/chapter_narration.pcm"
    const val ACTIVE_VERSES_FILE = "$TAKE_DIR/%s/active_verses.json"

    /**
     * Extensions of opaque legacy source-audio containers that can sit in [SOURCE_AUDIO_DIR]
     * alongside real audio.
     *
     * The legacy Android recorder stored source audio as an "Archive of Holding" (magic `aoh!` /
     * `aoc!`), an archive keyed by language/version/book/chapter rather than playable audio. These
     * are carried through import and export byte for byte so a decoder added later can read them in
     * place. Membership here means "copy, never interpret", which is why they belong neither to
     * `AudioFileFormat` nor to `AudioMetadataFileFormat`.
     */
    val LEGACY_SOURCE_AUDIO_EXTENSIONS = setOf("tr", "aoh", "aoc")

    fun isLegacySourceAudio(extension: String) =
        extension.lowercase() in LEGACY_SOURCE_AUDIO_EXTENSIONS
}
