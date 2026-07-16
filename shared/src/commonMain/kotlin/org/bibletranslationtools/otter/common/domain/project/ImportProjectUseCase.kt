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
@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package org.bibletranslationtools.otter.common.domain.project

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.shared.resources.Res
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.project.importer.BurritoImporterFactory
import org.bibletranslationtools.otter.common.domain.project.importer.IProjectImporter
import org.bibletranslationtools.otter.common.domain.project.importer.IProjectImporterFactory
import org.bibletranslationtools.otter.common.domain.project.importer.ImportOptions
import org.bibletranslationtools.otter.common.domain.project.importer.OngoingProjectImporter
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.bibletranslationtools.otter.common.domain.project.importer.TsImporterFactory
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import java.io.File
import java.lang.IllegalArgumentException
import javax.inject.Inject
import javax.inject.Provider

// Paths are relative to the Compose resources root (composeResources/), because the GL
// content ships as Compose Multiplatform resources and is read via Res.readBytes — NOT as
// JVM classpath resources. (Res paths do not include the composeResources/ prefix.)
const val SOURCES_JSON_FILE = "files/gl_sources.json"
const val SOURCE_PATH_TEMPLATE = "files/content/%s.zip"
// Build-generated manifest (generateEmbeddedSourcesManifest) of the source names whose zip
// actually got bundled — the wa-catalog manifest is partly stale, so this reflects reality.
const val EMBEDDED_SOURCES_FILE = "files/embedded_gl_sources.json"

class ImportProjectUseCase @Inject constructor(
    val burritoFactoryProvider: BurritoImporterFactory,
    val rcFactoryProvider: RCImporterFactory,
    val tsFactoryProvider: TsImporterFactory,
    val rcImporter: OngoingProjectImporter,
    val directoryProvider: IDirectoryProvider,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Throws(IllegalArgumentException::class)
    fun import(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions? = null
    ): Single<ImportResult> {
        return Single
            .fromCallable {
                val format = ProjectFormatIdentifier.getProjectFormat(file)
                getImporter(format)
            }
            .flatMap {
                it.import(file, callback, options)
            }
            .onErrorReturn {
                logger.error(
                    "Failed to import project file: $file. See exception detail below.",
                    it
                )
                ImportResult.FAILED
            }
    }

    fun import(file: File): Single<ImportResult> {
        return import(file, null, null)
    }

    fun sideloadSource(language: Language): Completable {
        return Single
            .fromCallable {
                getEmbeddedSource(language)
            }
            .subscribeOn(Schedulers.io())
            .doOnError {
                logger.error(
                    "Failed to get embedded source file for ${language.slug}",
                    it
                )
            }
            .flatMap { sourceFile ->
                import(sourceFile, null, null)
            }
            .ignoreElement()
    }

    private fun getEmbeddedSource(language: Language): File {
        val resourceName = glSources.find { it.languageCode == language.slug }?.name
        val pathToSource = SOURCE_PATH_TEMPLATE.format(resourceName)

        val sourceFile = runBlocking { Res.readBytes(pathToSource) }
            .inputStream()
            .use { input ->
                val tempFile = File.createTempFile(
                    resourceName,
                    ".zip",
                    directoryProvider.tempDirectory
                )
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
                tempFile
            }

        return sourceFile
    }

    fun isAlreadyImported(file: File): Boolean {
        return rcFactoryProvider
            .makeImporter()
            .isAlreadyImported(file)
    }

    fun isSourceAudioProject(file: File): Boolean {
        return directoryProvider.newFileReader(file).use {
            !it.exists(RcConstants.SELECTED_TAKES_FILE) && it.exists(RcConstants.SOURCE_MEDIA_DIR)
        }
    }

    fun getSourceMetadata(file: File): Maybe<ResourceMetadata> {
        return when (ProjectFormatIdentifier.getProjectFormat(file)) {
            ProjectFormat.RESOURCE_CONTAINER -> {
                rcImporter.getSourceMetadata(file)
            }

            else -> Maybe.empty()
        }
    }

    /**
     * Get the corresponding importer based on the project format.
     */
    private fun getImporter(format: ProjectFormat): IProjectImporter {
        val factory: IProjectImporterFactory = when(format) {
            ProjectFormat.SCRIPTURE_BURRITO -> burritoFactoryProvider
            ProjectFormat.RESOURCE_CONTAINER -> rcFactoryProvider
            ProjectFormat.TSTUDIO -> tsFactoryProvider
            else -> throw Exception("Unsupported project format.")
        }
        return factory.makeImporter()
    }

    companion object {
        val glSources: List<ResourceInfoSerializable> by lazy {
            // The GL-sources manifest ships as a Compose resource
            // (composeResources/files/gl_sources.json), so it is read via Res.readBytes —
            // NOT the JVM classpath (Compose packs its resources into a separate store the
            // classloader doesn't see). A missing resource degrades to "no embedded GL
            // sources" rather than crashing every caller (wizard, sideloadSource, import).
            val bytes = runCatching { runBlocking { Res.readBytes(SOURCES_JSON_FILE) } }
                .getOrNull() ?: return@lazy emptyList()
            val mapper = ObjectMapper(JsonFactory()).registerKotlinModule()
            val sources: List<ResourceInfoSerializable> = mapper.readValue(bytes)
            sources
        }

        /**
         * The source names (matching [ResourceInfoSerializable.name]) whose zip is actually
         * bundled, per the build-generated [EMBEDDED_SOURCES_FILE]. Empty if the manifest is
         * absent (e.g. the download task never ran), which fails closed — no embedded sources
         * offered rather than offering ones that can't be sideloaded.
         */
        val embeddedSourceNames: Set<String> by lazy {
            val bytes = runCatching { runBlocking { Res.readBytes(EMBEDDED_SOURCES_FILE) } }
                .getOrNull() ?: return@lazy emptySet()
            val mapper = ObjectMapper(JsonFactory()).registerKotlinModule()
            val names: List<String> = mapper.readValue(bytes)
            names.toSet()
        }
    }
}

data class ResourceInfoSerializable(val name: String, val languageCode: String, val url: String)