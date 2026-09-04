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
import org.bibletranslationtools.otter.common.domain.resourcecontainer.burrito.auth.AuthProvider
import org.bibletranslationtools.scriptureburrito.Checksum
import org.bibletranslationtools.scriptureburrito.CopyrightSchema
import org.bibletranslationtools.scriptureburrito.Flavor
import org.bibletranslationtools.scriptureburrito.Format
import org.bibletranslationtools.scriptureburrito.IdAuthoritiesSchema
import org.bibletranslationtools.scriptureburrito.IdAuthority
import org.bibletranslationtools.scriptureburrito.IdentificationSchema
import org.bibletranslationtools.scriptureburrito.IngredientSchema
import org.bibletranslationtools.scriptureburrito.IngredientsSchema
import org.bibletranslationtools.scriptureburrito.LanguageSchema
import org.bibletranslationtools.scriptureburrito.Languages
import org.bibletranslationtools.scriptureburrito.MetaVersionSchema
import org.bibletranslationtools.scriptureburrito.MetadataSchema
import org.bibletranslationtools.scriptureburrito.ScopeSchema
import org.bibletranslationtools.scriptureburrito.SoftwareAndUserInfoSchema
import org.bibletranslationtools.scriptureburrito.SourceMetaSchema
import org.bibletranslationtools.scriptureburrito.SourceMetadataSchema
import org.bibletranslationtools.scriptureburrito.TypeSchema
import org.bibletranslationtools.scriptureburrito.flavor.FlavorType
import org.bibletranslationtools.scriptureburrito.flavor.scripture.audio.AudioFlavorSchema
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * Keeps a WACS repo's `metadata.json` (Scripture-Burrito manifest) in sync on every publish: upserts
 * the `ingredients{}` entries for the audio + alignment files just published, and folds the chapter
 * number into `currentScope` (plan §6, "`metadata.json` drift").
 *
 * Every existing Burrito writer in this codebase (`ScriptureBurritoUtils.createBurritoManifest`,
 * `BurritoWrapperExporter.createAudioBurritoMetadata`) *rebuilds the whole manifest* from an
 * in-memory takes map on every call — none of them read-modify-write an already-populated on-disk
 * manifest, which is what publishing one chapter into an existing fork needs. This is the new piece
 * the plan calls for, built out of the same Burrito wire model (`MetadataSchema`/`IngredientsSchema`/
 * `ScopeSchema`/`Checksum`, `BURRITO_JSON` for the actual (de)serialization, done by the caller) —
 * reusing the artifact rather than inventing a second manifest format.
 */
object MetadataJsonWriter {

    /**
     * Returns an updated [MetadataSchema]: [existing] (parsed from the fork's on-disk
     * `metadata.json`) with the audio + alignment ingredients for [chapterNumber] upserted and
     * [chapterNumber] added to `currentScope[bookSlug]`; or, if [existing] is null (see
     * [freshManifest]), a new minimal manifest carrying just this chapter.
     */
    fun upsertChapter(
        existing: MetadataSchema?,
        workbook: Workbook,
        bookSlug: String,
        chapterNumber: Int,
        audioPath: String,
        audioFile: File,
        alignmentPath: String,
        alignmentFile: File,
    ): MetadataSchema {
        val manifest = existing ?: freshManifest(workbook)

        manifest.ingredients[audioPath] =
            buildIngredient(audioFile, bookSlug, chapterNumber, mimeTypeOf(audioFile), role = null)
        manifest.ingredients[alignmentPath] =
            buildIngredient(alignmentFile, bookSlug, chapterNumber, "application/json", role = "timing")

        manifest.type?.flavorType?.currentScope?.let { scope ->
            val chapters = (scope[bookSlug] ?: mutableListOf()).toMutableSet()
            chapters += chapterNumber.toString()
            scope[bookSlug] = chapters.map { it.toInt() }.sorted().map { it.toString() }.toMutableList()
        }

        return manifest
    }

    private fun buildIngredient(
        file: File,
        bookSlug: String,
        chapterNumber: Int,
        mimeType: String,
        role: String?,
    ): IngredientSchema = IngredientSchema().apply {
        this.mimeType = mimeType
        this.size = file.length().toInt()
        this.checksum = Checksum().apply { md5 = calculateMD5(file) }
        this.scope = ScopeSchema().apply { put(bookSlug, mutableListOf(chapterNumber.toString())) }
        this.role = role
    }

    private fun mimeTypeOf(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    private fun calculateMD5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /**
     * Best-effort fallback for a fork whose `metadata.json` doesn't exist or fails to parse (an
     * empty/newly-created official repo — the plan tracks "first-ever language repo" as an
     * out-of-scope admin step, but this keeps publish from hard-failing if it happens anyway). NOT
     * the primary path: in the normal fork-of-an-existing-official-repo flow, a real manifest is
     * already present and is parsed + mutated by [upsertChapter] instead of rebuilt here.
     */
    private fun freshManifest(workbook: Workbook): MetadataSchema {
        val language = workbook.target.language
        val authProvider = WacsMetadataAuthProvider()
        return SourceMetadataSchema(
            format = Format.SCRIPTURE_BURRITO,
            meta = SourceMetaSchema(
                dateCreated = Date.from(Instant.now()),
                version = MetaVersionSchema._1_0_0,
                defaultLocale = language.slug,
                generator = SoftwareAndUserInfoSchema().apply {
                    softwareName = "BTT Recorder"
                    softwareVersion = "1.0"
                }
            ),
            idAuthorities = authProvider.createIdAuthority(),
            identification = authProvider.createIdentification().apply {
                name["en"] = workbook.target.resourceMetadata.title
                abbreviation["en"] = workbook.target.resourceMetadata.identifier
            },
            confidential = false,
            copyright = CopyrightSchema(),
            type = TypeSchema(
                FlavorType(
                    name = Flavor.SCRIPTURE,
                    AudioFlavorSchema(),
                    currentScope = ScopeSchema()
                )
            ),
            languages = Languages().apply {
                add(
                    LanguageSchema(
                        tag = language.slug,
                        name = hashMapOf(language.slug to language.name, "en" to language.anglicizedName)
                    )
                )
            },
            ingredients = IngredientsSchema()
        )
    }
}

/**
 * Minimal [AuthProvider] used only by [MetadataJsonWriter.freshManifest]'s from-scratch fallback.
 * Mirrors the legacy JavaFX `WacsIdAuthority`, adapted to this codebase's kotlinx-serialization
 * Burrito model (no Jackson `ObjectNode` here — [IdentificationSchema.primary] is `@Transient` and
 * left at its default, so it never needs a JSON value).
 *
 * Deliberately NOT the DI-bound [AuthProvider]: nothing implements/binds one in this KMP port yet
 * (see `KoinModules.kt`'s note on `SourceProjectExporter` being unresolvable) — this is a
 * self-contained instance scoped to WACS `metadata.json` fallback-authoring only, independent of
 * that unrelated, still-open gap.
 */
private class WacsMetadataAuthProvider : AuthProvider {
    override fun createIdAuthority(): IdAuthoritiesSchema = IdAuthoritiesSchema().apply {
        this["wacs"] = IdAuthority().apply {
            id = "https://content.bibletranslationtools.org/"
            name = hashMapOf("en" to "Wycliffe Associates Content Service")
        }
    }

    override fun createIdentification(): IdentificationSchema = IdentificationSchema().apply {
        name = hashMapOf("en" to "Wycliffe Associates Content Service")
    }
}
