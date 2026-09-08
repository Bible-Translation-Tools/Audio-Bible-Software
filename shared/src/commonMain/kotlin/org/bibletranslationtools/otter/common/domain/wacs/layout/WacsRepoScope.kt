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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * One chapter's ingredients as actually listed in a WACS repo's `metadata.json` — the repo-relative
 * [audioPath] (the LFS-tracked file [org.bibletranslationtools.otter.common.domain.wacs.usecase.PullChapter]
 * downloads) plus any [companionPaths] (non-audio ingredients scoped to the same book+chapter, e.g.
 * timing/alignment JSON — materialized alongside the audio, best-effort).
 */
data class WacsChapterIngredient(
    val bookSlug: String,
    val chapterNumber: Int,
    val audioPath: String,
    val companionPaths: List<String>,
)

/** What a cloned WACS repo currently has available to pull, grouped by Scripture-Burrito book id. */
data class WacsRepoScope(
    val books: Map<String, List<WacsChapterIngredient>>,
)

/**
 * Reads [WacsRepoScope] from a cloned repo's `metadata.json` — deliberately NOT via this codebase's
 * vendored `kotlin-scripture-burrito` [org.bibletranslationtools.scriptureburrito.MetadataSchema].
 *
 * That model doesn't parse the real prototype's official repos: the live `AudioTranslation/audio-en-ulb-mrk`
 * seed data uses `type_specific` where the vendored model expects `type`, and its top-level
 * `type.flavorType.currentScope` is present but **empty** (`{"MRK": []}`) even though a chapter 1
 * audio ingredient genuinely exists — confirmed live against the prototype (2026-09) and consistent
 * with [org.bibletranslationtools.otter.common.domain.wacs.usecase.PublishChapterToWacsDesktopTest]'s
 * note that this same server-authored manifest shape doesn't round-trip through that model either.
 * `currentScope` is therefore not a reliable "what's available" source. Ground truth instead is the
 * `ingredients{}` map itself: each entry carries its own per-ingredient `scope` (book -> chapter
 * list) and `mimeType`, which is schema-stable across every manifest seen so far (M2's own writer —
 * [MetadataJsonWriter] — and the prototype's seed data both shape `ingredients{}` the same way, even
 * though they disagree on the top-level `type`/`type_specific` wrapper). Reading only that map with
 * generic [kotlinx.serialization.json] element navigation sidesteps the whole-document schema
 * mismatch entirely.
 *
 * An ingredient is treated as this chapter's audio if its `mimeType` starts with `audio/`; every
 * other ingredient scoped to the same (book, chapter) is a companion file (timing/alignment JSON,
 * whatever extension the publisher used — the prototype's seed data uses `.timing`, M2's own writer
 * uses `.alignment.json`; both are just "the other file for this chapter" here).
 */
object WacsScopeReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** @throws Exception if [metadataFile] isn't valid JSON or has no `ingredients` object — callers classify. */
    fun read(metadataFile: File): WacsRepoScope {
        val root = json.parseToJsonElement(metadataFile.readText()).jsonObject
        val ingredients = root["ingredients"]?.jsonObject ?: return WacsRepoScope(emptyMap())

        data class Entry(val path: String, val book: String, val chapter: Int, val isAudio: Boolean)

        val entries = mutableListOf<Entry>()
        for ((path, value) in ingredients) {
            val obj = value.jsonObject
            val mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val scope = obj["scope"]?.jsonObject ?: continue
            for ((book, chaptersElement) in scope) {
                val chapterNumbers = chaptersElement.jsonArray.mapNotNull {
                    it.jsonPrimitive.contentOrNull?.toIntOrNull()
                }
                for (chapter in chapterNumbers) {
                    entries += Entry(path, book, chapter, mimeType.startsWith("audio/"))
                }
            }
        }

        val books: Map<String, List<WacsChapterIngredient>> = entries
            .groupBy { it.book to it.chapter }
            .mapNotNull { (bookChapter, group) ->
                val (book, chapter) = bookChapter
                val audio = group.firstOrNull { it.isAudio } ?: return@mapNotNull null
                val companions = group.filterNot { it.isAudio }.map { it.path }
                WacsChapterIngredient(book, chapter, audio.path, companions)
            }
            .groupBy { it.bookSlug }
            .mapValues { (_, list) -> list.sortedBy { it.chapterNumber } }

        return WacsRepoScope(books)
    }
}
