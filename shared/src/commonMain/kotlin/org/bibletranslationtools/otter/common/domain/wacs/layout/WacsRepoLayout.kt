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
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
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

    /**
     * The same naming rule as [repoName], but from a [WorkbookDescriptor] — the lightweight listing
     * a project picker already has (no need to open a full [Workbook]) — used by
     * [org.bibletranslationtools.otter.common.domain.wacs.usecase.RestoreChapterFromWacs] to find
     * which local project (if any) a WACS repo restores into. Null if [descriptor]'s target
     * collection has no resource-container metadata yet (shouldn't happen for a created project, but
     * this is a read of someone else's data, not a guarantee this class controls).
     */
    fun repoNameOrNull(descriptor: WorkbookDescriptor): String? {
        val targetMetadata = descriptor.targetCollection.resourceContainer ?: return null
        val lang = targetMetadata.language.slug.lowercase(Locale.US)
        val edition = targetMetadata.identifier.lowercase(Locale.US)
        val book = descriptor.targetCollection.slug.lowercase(Locale.US)
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

    /**
     * The reverse of [repoName]/[repoNameOrNull]: given [repoName] plus the target-language slug
     * and book slug **already known independently of parsing this same string** (from
     * `metadata.json`'s `languages[].tag` — see [WacsScopeReader.readLanguageTags] — and the
     * `ingredients{}` scope — see [WacsScopeReader.read]), extracts the middle "edition"
     * (resource-identifier) segment, e.g. `"ulb"` out of `"audio-en-ulb-mrk"`.
     *
     * This is deliberately NOT a general `"audio-<lang>-<edition>-<book>".split('-')` parser:
     * several real gateway-language slugs in this app's own catalog contain hyphens themselves
     * (`es-419`, `pt-br`, `ar-x-dcv`, ...), so the naming rule is ambiguous to parse in isolation —
     * `audio-es-419-ulb-mrk` could just as easily be lang `es` + edition `419-ulb` as lang `es-419`
     * + edition `ulb`. Requiring the caller to already know [languageSlug] and [bookSlug] from a
     * source *other than this string* (metadata.json's own fields) removes the ambiguity: what's
     * left in the middle once the known prefix/suffix are stripped IS the edition, unambiguously.
     *
     * Returns null if [repoName] doesn't actually start with `audio-$languageSlug-` and end with
     * `-$bookSlug` (case-insensitively) — e.g. a metadata.json whose `languages` tag disagrees with
     * the repo's own name, which should not be trusted to guess a source from either.
     */
    fun editionFromRepoName(repoName: String, languageSlug: String, bookSlug: String): String? {
        val lang = languageSlug.lowercase(Locale.US)
        val book = bookSlug.lowercase(Locale.US)
        val name = repoName.lowercase(Locale.US)
        val prefix = "audio-$lang-"
        val suffix = "-$book"
        if (!name.startsWith(prefix) || !name.endsWith(suffix) || name.length <= prefix.length + suffix.length) {
            return null
        }
        return name.removePrefix(prefix).removeSuffix(suffix).takeIf { it.isNotBlank() }
    }

    const val METADATA_PATH = "metadata.json"
    const val GITATTRIBUTES_PATH = ".gitattributes"

    /** The `.gitattributes` line that routes one audio extension through git-LFS. */
    fun gitattributesLine(extension: String): String =
        "*.${extension.lowercase(Locale.US)} filter=lfs diff=lfs merge=lfs -text"
}
