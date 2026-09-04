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

import org.bibletranslationtools.otter.common.data.workbook.Workbook
import java.util.Locale

/**
 * Centralizes the WACS repo naming rule and in-repo path conventions (see the plan doc, §1/§4/§9)
 * so the publish client and the server (the Catalog Indexer reading `metadata.json`) agree on both.
 * [org.bibletranslationtools.otter.common.domain.wacs.usecase.PublishChapterToWacs] and
 * [MetadataJsonWriter] are the only callers — nothing else should hardcode a WACS path.
 */
object WacsRepoLayout {

    /**
     * `audio-<target-language-slug>-<resource-identifier>-<book-slug>`, e.g. `audio-en-ulb-mrk` —
     * matches the prototype's official test repo (`AudioTranslation/audio-en-ulb-mrk`).
     *
     * The plan's naming rule allows an optional book segment ("audio-<lang>-<edition>[-<book>]")
     * for a hypothetical non-book-scoped project; a [Workbook] in this app is always exactly one
     * Scripture-Burrito "project" (one USFM book), so the book segment is always present here.
     * Revisit if a non-book-scoped project type is ever added.
     */
    fun repoName(workbook: Workbook): String {
        val lang = workbook.target.language.slug.lowercase(Locale.US)
        val edition = workbook.target.resourceMetadata.identifier.lowercase(Locale.US)
        val book = workbook.target.slug.lowercase(Locale.US)
        return "audio-$lang-$edition-$book"
    }

    /** The Scripture-Burrito book id: the uppercase USFM book slug, e.g. `MRK`. */
    fun bookSlug(workbook: Workbook): String = workbook.target.slug.uppercase(Locale.US)

    private fun chapterSegment(chapterNumber: Int): String = chapterNumber.toString().padStart(2, '0')

    /**
     * Repo-relative path of the LFS-tracked audio ingredient for one chapter, e.g.
     * `ingredients/MRK/01.wav`. This is the path [PublishChapterToWacs][org.bibletranslationtools.otter.common.domain.wacs.usecase.PublishChapterToWacs]
     * writes an LFS *pointer* to — never the real audio bytes (see the plan's §0 ordering rule).
     */
    fun audioIngredientPath(bookSlug: String, chapterNumber: Int, extension: String): String =
        "ingredients/$bookSlug/${chapterSegment(chapterNumber)}.${extension.lowercase(Locale.US)}"

    /**
     * Repo-relative path of the (non-LFS, real-content) Scripture-Burrito alignment JSON for one
     * chapter — this IS the "timing" file; there is no separate `.timing` format (the plan
     * explicitly corrects the client integration guide on this point).
     */
    fun alignmentIngredientPath(bookSlug: String, chapterNumber: Int): String =
        "ingredients/$bookSlug/${chapterSegment(chapterNumber)}.alignment.json"

    const val METADATA_PATH = "metadata.json"
    const val GITATTRIBUTES_PATH = ".gitattributes"

    /** The `.gitattributes` line that routes one audio extension through git-LFS. */
    fun gitattributesLine(extension: String): String =
        "*.${extension.lowercase(Locale.US)} filter=lfs diff=lfs merge=lfs -text"
}
