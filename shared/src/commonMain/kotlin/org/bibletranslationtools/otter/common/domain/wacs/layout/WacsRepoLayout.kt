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
 * Centralizes the WACS repo naming rule and in-repo path conventions so the client and the
 * publish pipeline agree on both; nothing else should hardcode a WACS path. The chapter rules are
 * the pipeline's commit contract (its `CLIENT_INTEGRATION_GUIDE.md` §3), which its server-side
 * check enforces on every pull request.
 */
object WacsRepoLayout {

    /**
     * The per-book name `audio-<target-language-slug>-<resource-identifier>-<book-slug>` (e.g.
     * `audio-en-ulb-mrk`) that restore matches a local project by, from a [WorkbookDescriptor].
     * Publishing no longer names repos this way: the pipeline names one repo per language and
     * translation (`en_ulb`) and returns it. Null if [descriptor]'s target collection has no
     * resource-container metadata.
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

    /** The audio formats a chapter may be in; a chapter has one of them. */
    val AUDIO_EXTENSIONS = listOf("wav", "flac")

    /** The trailer that names the chapter a commit carries; the pipeline requires exactly one. */
    const val CHAPTER_TRAILER = "Audio-Chapter"

    /** The directory published chapters are in. */
    const val MEDIA_DIRECTORY = "media"

    /** The directory a book's chapters are in: `media/mrk`. */
    fun bookDirectory(bookSlug: String): String = "$MEDIA_DIRECTORY/${bookSlug.lowercase(Locale.US)}"

    /**
     * Where a chapter file named [fileName] goes: `media/mrk/en_ulb_mrk_c01.wav`, or its timing file
     * `media/mrk/en_ulb_mrk_c01.json`. The name comes from
     * [FileNamer.chapterFileName][org.bibletranslationtools.otter.common.domain.content.FileNamer.chapterFileName].
     * Publishing writes an LFS *pointer* at the audio path, never the audio bytes.
     */
    fun chapterFilePath(bookSlug: String, fileName: String): String = "${bookDirectory(bookSlug)}/$fileName"

    /**
     * Whether [path] is one of chapter [chapterNumber]'s files in [bookSlug]: its audio in any format
     * or its timing file, whatever language and edition its name carries.
     */
    fun isChapterFile(path: String, bookSlug: String, chapterNumber: Int): Boolean {
        val book = Regex.escape(bookSlug.lowercase(Locale.US))
        val extensions = (AUDIO_EXTENSIONS + "json").joinToString("|")
        return Regex("^${Regex.escape(MEDIA_DIRECTORY)}/$book/[^/]+_${book}_c0*$chapterNumber\\.($extensions)$").matches(path)
    }

    /** How the pipeline names a chapter in commits and pull requests: `MRK 1`. */
    fun chapterId(bookSlug: String, chapterNumber: Int): String = "${bookSlug.uppercase(Locale.US)} $chapterNumber"

    /** A chapter commit's message: the chapter, a blank line, and its [CHAPTER_TRAILER]. */
    fun chapterCommitMessage(bookSlug: String, chapterNumber: Int): String {
        val id = chapterId(bookSlug, chapterNumber)
        return "$id\n\n$CHAPTER_TRAILER: $id"
    }

    /** The chapters a commit [message] says it carries, by its [CHAPTER_TRAILER] lines. */
    fun chaptersInCommitMessage(message: String): List<String> =
        Regex("^$CHAPTER_TRAILER:\\s*(.+?)\\s*$", RegexOption.MULTILINE).findAll(message).map { it.groupValues[1] }.toList()

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
