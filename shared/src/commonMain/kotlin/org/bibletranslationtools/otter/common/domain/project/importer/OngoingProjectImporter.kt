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
package org.bibletranslationtools.otter.common.domain.project.importer

import java.util.UUID
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionOrder
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.bibletranslationtools.otter.common.api.io.zip.IFileReader
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.Chunkification
import org.bibletranslationtools.otter.common.data.OratureFileFormat
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Contributor
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.primitives.SerializableProjectMode
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.Translation
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.collections.UpgradeBookEdition
import org.bibletranslationtools.otter.common.domain.project.BackupEditionRecord
import org.bibletranslationtools.otter.common.domain.project.BackupEditions
import org.bibletranslationtools.otter.common.domain.project.BackupHeldChapter
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.domain.content.ConcatenateAudio
import org.bibletranslationtools.otter.common.domain.content.FileNamer
import org.bibletranslationtools.otter.common.domain.content.FileNamer.Companion.takeFilenamePattern
import org.bibletranslationtools.otter.common.domain.mapper.mapToMetadata
import org.bibletranslationtools.otter.common.domain.project.ProjectAppVersion
import org.bibletranslationtools.otter.common.domain.project.TakeCheckingStatusMap
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportException
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.utils.SELECTED_TAKES_FROM_DB
import org.bibletranslationtools.otter.common.utils.computeFileChecksum
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.resourcecontainer.entity.Project
import org.wycliffeassociates.resourcecontainer.entity.Source
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.SerializationException
import org.bibletranslationtools.otter.common.OTTER_JSON
import org.bibletranslationtools.otter.common.data.CHUNKIFICATION
import org.bibletranslationtools.otter.common.domain.project.TAKE_CHECKING

class OngoingProjectImporter(
    private val directoryProvider: IDirectoryProvider,
    private val resourceMetadataRepository: IResourceMetadataRepository,
    private val workbookRepository: IWorkbookRepository,
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository,
    private val collectionRepository: ICollectionRepository,
    private val contentRepository: IContentRepository,
    private val takeRepository: ITakeRepository,
    private val languageRepository: ILanguageRepository,
    private val resourceRepository: IResourceRepository,
    private val createProjectUseCase: CreateProject,
    private val concatAudioUseCase: ConcatenateAudio,
    private val backupEditions: BackupEditions,
    private val upgradeBookEdition: UpgradeBookEdition,
    private val editionFingerprinter: EditionFingerprinter
) : RCImporter(directoryProvider, resourceMetadataRepository) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    private val contentCache = mutableMapOf<ContentSignature, Content>()
    private var projectName = ""
    private var projectAppVersion = ProjectAppVersion.THREE
    private var projectMode: ProjectMode = ProjectMode.TRANSLATION
    private var takesInChapterFilter: Map<String, Int>? = null
    private var takesCheckingMap: TakeCheckingStatusMap = mapOf()
    private var takesInSelectedFile = setOf<String>()
    private var completedChapters = listOf<Int>() // for Ot1 projects
    private var takesToCompile = mutableMapOf<Int, List<File>>() // for compiling verses of incomplete chapter in Ot1
    private var migratedSelectedTakes = listOf<String>() // list of all selected take paths extracted from Ot1 database
    /** The source book of the project already here that the backup is merged into, if there is one. */
    private var existingProjectSource: Collection? = null

    override fun import(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): Single<ImportResult> {
        val isOngoingProject = isResumableProject(file)
        if (!isOngoingProject) {
            return super.passToNextImporter(file, callback, options)
        }
        projectName = ""
        takesInChapterFilter = null
        takesCheckingMap = mapOf()
        takesInSelectedFile = setOf()
        completedChapters = listOf()
        takesToCompile = mutableMapOf()
        migratedSelectedTakes = listOf()
        existingProjectSource = null
        contentCache.clear()

        return Single
            .fromCallable { projectExists(file) }
            .doOnError {
                logger.error("Error while checking whether project already exists.", it)
            }
            .flatMap { exists ->
                val takesByChapterInProject = fetchTakesInRC(file)

                if (exists && callback != null) {
                    val availableChapters = takesByChapterInProject.values.distinct().sorted()
                    val selectedChapters = getUserSelectedChapter(availableChapters, callback)
                        ?: return@flatMap Single.just(ImportResult.ABORTED)

                    takesInChapterFilter = takesByChapterInProject.filterValues { it in selectedChapters }
                } else {
                    takesInChapterFilter = takesByChapterInProject // accept all takes
                }

                importResumableProject(file, callback)
            }
            .subscribeOn(Schedulers.io())
    }

    private fun isResumableProject(rcFile: File): Boolean {
        return try {
            hasInProgressMarker(rcFile)
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Whether the project in [file] already exists here: same book and target language, translated
     * from the same source (language and identifier). The source edition may differ, since an
     * edition change doesn't make it a different project.
     */
    private fun projectExists(file: File): Boolean {
        ResourceContainer.load(file).use { rc ->
            rc.manifest.dublinCore.let {
                val source = it.source.firstOrNull() ?: return false
                val sourceLanguageSlug = source.language

                val languageSlug = it.language.identifier
                val projectSlug = rc.manifest.projects.first().let { p ->
                    projectName = p.title
                    p.identifier
                }

                val projects = workbookRepository.getProjects().blockingGet()
                return projects.firstOrNull { existingProject ->
                    sourceLanguageSlug == existingProject.source.language.slug &&
                        source.identifier == existingProject.source.resourceMetadata.identifier &&
                        languageSlug == existingProject.target.language.slug &&
                        projectSlug == existingProject.target.slug
                }?.let {
                    workbookRepository.closeWorkbook(it)
                    it.projectFilesAccessor.isInitialized().also { initialized ->
                        if (initialized) existingProjectSource = it.source.toCollection()
                    }
                } ?: false
            }
        }
    }

    private fun getUserSelectedChapter(
        availableChapters: List<Int>,
        callback: ProjectImporterCallback
    ): List<Int>? {
        val callbackParam = ImportCallbackParameter(availableChapters, projectName)
        return callback.onRequestUserInput(callbackParam).blockingGet().chapters
    }

    private fun fetchTakesInRC(file: File): Map<String, Int> {
        ResourceContainer.load(file).use { rc ->
            val extensionFilter = AudioFileFormat.values().map { it.extension }
            val fileStreamMap = rc.accessor.getInputStreams(".", extensionFilter)
            try {
                val takesMap: Map<String, Int> = fileStreamMap.keys
                    .mapNotNull { path ->
                        parseNumbers(path)?.let {
                            Pair(path, it)
                        }
                    }
                    .associate {
                        Pair(it.first, it.second.contentSignature.chapter)
                    }

                return takesMap
            } finally {
                fileStreamMap.values.forEach { it.close() }
            }
        }
    }

    fun getSourceMetadata(resourceContainer: File): Maybe<ResourceMetadata> {
        return Maybe.fromCallable {
            val manifest: Manifest = ResourceContainer.load(resourceContainer).use { it.manifest }
            val manifestSources = manifest.dublinCore.source.toSet()
            val manifestProject = manifest.projects.single()
            val sourceCollection = findSourceCollection(manifestSources, manifestProject)
            sourceCollection.resourceContainer
        }
    }

    private fun importResumableProject(
        resourceContainer: File,
        callback: ProjectImporterCallback?
    ): Single<ImportResult> {
        return Single.fromCallable {
            try {
                val manifest: Manifest = ResourceContainer.load(resourceContainer).use { it.manifest }
                val manifestSources = manifest.dublinCore.source.toSet()
                val manifestProject = try {
                    manifest.projects.single()
                } catch (t: Throwable) {
                    logger.error("In-progress import must have 1 project, but this has {}", manifest.projects.count())
                    throw ImportException(ImportResult.INVALID_RC)
                }

                callback?.onNotifyProgress(
                    localizeKey = "loadingSomething",
                    message = "${manifest.dublinCore.language.identifier}_${manifestProject.identifier}",
                    percent = 10.0
                )
                directoryProvider.newFileReader(resourceContainer).use { fileReader ->
                    val editionRecord = backupEditions.read(fileReader)
                        ?.takeIf { it.book == manifestProject.identifier }
                    try {
                        callback?.onNotifyProgress(localizeKey = "importingSource", percent = 25.0)
                        // Import Sources even if existing source exists in order to potentially merge source audio.
                        // Each is its own edition, beside any other edition of it already here.
                        importSources(fileReader)
                        importSources(fileReader, RcConstants.OWN_SOURCE_DIR)
                    } catch (e: ImportException) {
                        logger.error("Error importing source of resumable project", e)
                    }
                    // Merging into a project already here keeps it on its edition. Otherwise the
                    // project attaches to the edition it was backed up from (S11-Q3).
                    val sourceCollection = existingProjectSource
                        ?: backedUpSourceCollection(editionRecord, manifestSources, manifestProject, fileReader)

                    val metadata = languageRepository
                        .getBySlug(manifest.dublinCore.language.identifier)
                        .map { language ->
                            manifest.dublinCore.mapToMetadata(resourceContainer, language)
                        }
                        .blockingGet()

                    val derived = importResumableProject(
                        fileReader, metadata, manifestProject, sourceCollection, callback,
                        heldBack = editionRecord.takeIf { existingProjectSource == null }?.heldBack.orEmpty()
                    )
                    val workbookDescriptor = workbookDescriptorRepository.getAll().blockingGet().firstOrNull {
                        it.targetCollection.id == derived.id && it.sourceCollection.id == sourceCollection.id
                    }
                    callback?.onNotifySuccess(
                        manifest.dublinCore.language.title,
                        manifestProject.title,
                        workbookDescriptor
                    )
                }

                ImportResult.SUCCESS
            } catch (e: ImportException) {
                e.result
            } catch (e: Exception) {
                logger.error("Failed to import in-progress project", e)
                ImportResult.FAILED
            }
        }
    }

    private fun importResumableProject(
        fileReader: IFileReader,
        metadata: ResourceMetadata,
        manifestProject: Project,
        sourceCollection: Collection,
        callback: ProjectImporterCallback?,
        heldBack: List<BackupHeldChapter> = emptyList()
    ): Collection {
        val sourceMetadata = sourceCollection.resourceContainer!!
        projectMode = getProjectMode(fileReader, metadata.language, sourceCollection.resourceContainer!!.language)
        val isVerseByVerse = projectMode != ProjectMode.TRANSLATION ||
                projectAppVersion == ProjectAppVersion.ONE

        val derivedProject = createDerivedProjects(
            metadata.language,
            sourceCollection,
            projectMode,
            isVerseByVerse
        )

        restoreHeldChapters(derivedProject, heldBack)

        val translation = createTranslation(sourceMetadata.language, metadata.language)

        // The project's folder is found from its own row, as the rest of the app finds it; the
        // backup's manifest may carry another version (it isn't overwritten to match any more).
        val projectFilesAccessor = ProjectFilesAccessor(
            directoryProvider,
            sourceMetadata,
            derivedProject.resourceContainer ?: metadata,
            derivedProject
        )

        projectFilesAccessor.initializeResourceContainerInDir()
        projectFilesAccessor.setProjectMode(projectMode)
        takesInSelectedFile = prepareSelectedTakes(fileReader)

        callback?.onNotifyProgress(localizeKey = "copyingSource", percent = 40.0)
        projectFilesAccessor.copySourceFiles(fileReader)
        if (projectAppVersion == ProjectAppVersion.ONE) {
            callback?.onNotifyProgress(localizeKey = "loading_content", percent = 60.0)
            deriveChapterContentFromVerses(derivedProject, projectFilesAccessor)
            setMigrationInfo(takesInSelectedFile)
        }
        importContributorInfo(metadata, projectFilesAccessor)
        importChunks(
            derivedProject,
            projectFilesAccessor,
            fileReader
        )
        callback?.onNotifyProgress(localizeKey = "importingTakes", percent = 80.0)

        importTakes(
            fileReader,
            derivedProject,
            manifestProject,
            metadata,
            sourceCollection,
            projectFilesAccessor
        )

        projectFilesAccessor.copyInProgressNarrationFiles(fileReader, manifestProject)
            .doOnError { e ->
                logger.error("Error in importInProgressFiles, project: $derivedProject, manifestProject: $manifestProject")
                logger.error("metadata: $metadata, sourceMetadata: ${sourceCollection.resourceContainer}")
                logger.error("sourceCollection: $sourceCollection", e)
            }
            .blockingSubscribe()

        translation.modifiedTs = LocalDateTime.now()
        languageRepository.updateTranslation(translation).subscribe()
        resetChaptersWithoutTakes(fileReader, derivedProject, projectMode)

        callback?.onNotifyProgress(localizeKey = "finishingUp", percent = 99.0)

        return derivedProject
    }

    private fun setMigrationInfo(selectedTakesInProject: Set<String>) {
        migratedSelectedTakes = directoryProvider.tempDirectory.resolve(SELECTED_TAKES_FROM_DB).let {
            if (it.exists()) it.readLines() else listOf()
        }
        val selectedChaptersFromProjectFile = selectedTakesInProject
            .mapNotNull { parseNumbers(it) }
            .filter { sig ->
                sig.contentSignature.verse == null
            }
            .map { it.contentSignature.chapter }

        val selectedTakeNames = migratedSelectedTakes.map { File(it).name }
        completedChapters = takesInChapterFilter
            ?.filterKeys { takePath ->
                val isChapter = parseNumbers(takePath)
                    ?.contentSignature
                    ?.let { sig -> sig.verse == null } == true

                isChapter && File(takePath).name in selectedTakeNames
            }
            ?.values
            ?.union(selectedChaptersFromProjectFile)
            ?.toList()
            ?: listOf()
    }

    private fun getProjectMode(
        fileReader: IFileReader,
        targetLanguage: Language,
        sourceLanguage: Language
    ): ProjectMode {
        if (fileReader.exists(RcConstants.PROJECT_MODE_FILE)) {
            projectAppVersion = ProjectAppVersion.THREE
            fileReader.bufferedReader(RcConstants.PROJECT_MODE_FILE).use {
                val serialized: SerializableProjectMode =
                    OTTER_JSON.decodeFromString(SerializableProjectMode.serializer(), it.readText())
                return serialized.mode
            }
        }
        // project mode does not exist until Orature 3
        projectAppVersion = ProjectAppVersion.ONE
        return if (targetLanguage.slug == sourceLanguage.slug) {
            ProjectMode.NARRATION
        } else {
            ProjectMode.TRANSLATION
        }
    }

    private fun importChunks(project: Collection, accessor: ProjectFilesAccessor, fileReader: IFileReader) {
        accessor.copyChunkFile(fileReader)

        val chunkFileExists = fileReader.exists(RcConstants.CHUNKS_FILE)
        val chunks: Chunkification = if (chunkFileExists) {
            try {
                fileReader.stream(RcConstants.CHUNKS_FILE).let { input ->
                    OTTER_JSON.decodeFromString(CHUNKIFICATION, input.readBytes().decodeToString())
                }
            } catch (e: SerializationException) {
                // empty file
                Chunkification()
            }
        } else {
            Chunkification()
        }

        val chapters = collectionRepository.getChildren(project).blockingGet()
        chapters.forEach { chapter ->
            if (chunks.containsKey(chapter.sort)) {
                val contents = chunks[chapter.sort] ?: listOf()
                contentRepository.deleteForCollection(chapter).blockingAwait()
                contentRepository.insertForCollection(contents, chapter).blockingGet()
            }
        }
    }

    /**
     * Populates all contents under the filtered chapters as verse-by-verse.
     * This method is used when importing projects from Orature 1
     */
    private fun deriveChapterContentFromVerses(
        project: Collection,
        projectAccessor: ProjectFilesAccessor
    ) {
        val filteredChapters = takesInChapterFilter?.values?.distinct()
        collectionRepository.getChildren(project).blockingGet()
            .filter { filteredChapters == null || it.sort in filteredChapters }
            .forEach { chapter ->
                val contents = projectAccessor.getChapterContent(project.slug, chapter.sort)
                    .mapIndexed { index, content ->
                        content.sort = index + 1
                        content.draftNumber = 1
                        content
                    }
                contentRepository.deleteForCollection(chapter, ContentType.TEXT)
                    .andThen(
                        contentRepository.getByCollection(chapter)
                    )
                    .flattenAsObservable { it }
                    .flatMapCompletable { content ->
                        takeRepository.deleteForContent(content)
                    }
                    .andThen(
                        contentRepository.insertForCollection(contents, chapter) // derive contents
                    )
                    .ignoreElement()
                    .blockingGet()
            }
    }

    private fun resetChaptersWithoutTakes(fileReader: IFileReader, derivedProject: Collection, mode: ProjectMode) {
        if (mode != ProjectMode.TRANSLATION) {
            return
        }

        val chunkFileExists = fileReader.exists(RcConstants.CHUNKS_FILE)
        val chapterStarted = if (!chunkFileExists) {
            listOf()
        } else {
            try {
                fileReader.stream(RcConstants.CHUNKS_FILE).let { input ->
                    val chunks: Chunkification = OTTER_JSON.decodeFromString(CHUNKIFICATION, input.readBytes().decodeToString())
                    val chapters = chunks.map { it.key }
                    chapters
                }
            } catch (e: Exception) {
                listOf()
            }
        }
        val chaptersNotStarted = collectionRepository
            .collectionsWithoutTakes(derivedProject).blockingGet()
            .filterNot { chapterStarted.contains(it.sort) }

        chaptersNotStarted.forEach { contentRepository.deleteForCollection(it).blockingGet() }
    }

    private fun importContributorInfo(
        metadata: ResourceMetadata,
        projectFilesAccessor: ProjectFilesAccessor
    ) {
        val contributors = ResourceContainer.load(metadata.path).use { rc ->
            rc.manifest.dublinCore.contributor.map { Contributor(it) }
        }
        if (contributors.isNotEmpty()) {
            projectFilesAccessor.setContributorInfo(contributors)
        }
    }

    private fun importTakes(
        fileReader: IFileReader,
        project: Collection,
        manifestProject: Project,
        metadata: ResourceMetadata,
        sourceCollection: Collection,
        projectFilesAccessor: ProjectFilesAccessor
    ) {
        val collectionForTakes = when (metadata.type) {
            // Work around the quirk that resource takes are attached to source, not target project
            ContainerType.Help -> sourceCollection
            else -> project
        }
        val sourceMetadata = sourceCollection.resourceContainer!!
        takesCheckingMap = parseCheckingStatusFile(fileReader)

        projectFilesAccessor.copySelectedTakesFile(fileReader)
        // Staged first, then placed one by one: an imported recording never overwrites one already
        // in the project. It comes in as a new take, renumbered if its number is taken.
        val staging = File(directoryProvider.tempDirectory, "take-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            projectFilesAccessor.copyTakeFiles(fileReader, manifestProject, ::takeCopyFilter, destination = staging)
                .doOnError { e ->
                    logger.error("Error in importTakes, project: $project, manifestProject: $manifestProject")
                    logger.error("metadata: $metadata, sourceMetadata: $sourceMetadata")
                    logger.error("sourceCollection: $sourceCollection", e)
                }
                .blockingSubscribe { stagedTakeFile ->
                    insertTake(
                        File(stagedTakeFile),
                        staging,
                        projectFilesAccessor.audioDir,
                        collectionForTakes,
                        sourceMetadata,
                        takesInSelectedFile
                    )
                }
        } finally {
            staging.deleteRecursively()
        }

        val isNarrationMigration = projectAppVersion == ProjectAppVersion.ONE && projectMode == ProjectMode.NARRATION
        if (isNarrationMigration) {
            takesToCompile.forEach { (chapter, takeFiles) ->
                compileIncompleteChapterNarration(chapter, takeFiles, project, metadata)
            }
        }
    }

    private fun prepareSelectedTakes(fileReader: IFileReader): Set<String> {
        return fileReader
            .bufferedReader(RcConstants.SELECTED_TAKES_FILE)
            .useLines { it.toSet() }
            .mapNotNull { takePath ->
                parseNumbers(takePath)?.let {
                    Pair(takePath, it)
                }
            }
            .filter { (takePath, signature) ->
                takesInChapterFilter?.values?.contains(signature.contentSignature.chapter)
                    ?: true
            }
            .map { (path, _) ->
                path
            }
            .toSet()
    }

    private fun parseCheckingStatusFile(fileReader: IFileReader) =
        if (fileReader.exists(RcConstants.CHECKING_STATUS_FILE)) {
            fileReader.stream(RcConstants.CHECKING_STATUS_FILE).use { stream ->
                OTTER_JSON.decodeFromString(TAKE_CHECKING, stream.readBytes().decodeToString())
                    .mapKeys { File(it.key).name } // take name as key
            }
        } else {
            mapOf()
        }

    /**
     * Filters only takes that are chosen to import (based on the callback result)
     */
    private fun takeCopyFilter(path: String): Boolean {
        return takesInChapterFilter?.let { takesInChapter ->
            val takePath = takesInChapter.keys.firstOrNull { filterPath ->
                File(filterPath).name == File(path).name
            }
            takePath != null
        } ?: true
    }

    /**
     * Places [staged], a take file copied from the import into [staging], in the project.
     *
     * It never overwrites a take already there: it comes in as a new take, numbered after the
     * content's existing takes and renamed to match when its own number is taken. Only a file
     * byte-for-byte the same as an existing take of that content is not added again (re-importing
     * one's own backup); the existing take is selected instead if the import selects it.
     */
    private fun insertTake(
        staged: File,
        staging: File,
        projectAudioDir: File,
        project: Collection,
        metadata: ResourceMetadata,
        selectedTakes: Set<String>
    ) {
        // Selection and checking status are recorded under the take's name in the import.
        val importedPath = staged.relativeTo(staging).invariantSeparatorsPath
        val parsed = parseNumbers(importedPath)
        val chunk = parsed?.let { (sig, _) -> getContent(sig, project, metadata) }
        if (parsed == null || chunk == null) {
            val kept = moveIntoProject(staged, projectAudioDir.resolve(importedPath))
            if (parsed != null) {
                // Restored onto an edition without this verse: the file is kept in the project's
                // folder, but nothing in the project refers to it.
                logger.warn(
                    "Restoring ${project.slug}: take $kept has no place in the project " +
                        "(chapter ${parsed.contentSignature.chapter}, verse ${parsed.contentSignature.verse}), so it isn't listed"
                )
            }
            return
        }
        val (sig, importedNumber) = parsed
        // An Orature 1 migration names selected takes by where they land in the project.
        val landing = projectAudioDir.resolve(importedPath)
        val isSelected = importedPath in selectedTakes ||
            landing.path in migratedSelectedTakes || landing.absolutePath in migratedSelectedTakes
        val existing = takeRepository.getByContent(chunk, includeDeleted = true).blockingGet()

        val checksum = computeFileChecksum(staged)
        existing.firstOrNull { it.path.exists() && computeFileChecksum(it.path) == checksum }?.let { same ->
            staged.delete()
            if (isSelected && same.deleted == null) {
                chunk.selectedTake = same
                contentRepository.update(chunk).blockingAwait()
            }
            return
        }

        val target = projectAudioDir.resolve(importedPath)
        val numberTaken = existing.any { it.number == importedNumber } || target.exists()
        val takeNumber = if (numberTaken) nextTakeNumber(existing, target) else importedNumber
        val file = moveIntoProject(staged, if (numberTaken) target.withTakeNumber(takeNumber) else target).canonicalFile
        if (numberTaken) logger.info("Imported take $importedPath added as take $takeNumber (${file.name})")

        val now = LocalDate.now()
        val relativeFile = File(importedPath)

        val checkingStatus = when {
            projectAppVersion.ordinal >= ProjectAppVersion.THREE.ordinal -> takesCheckingMap[relativeFile.name]

            completedChapters.contains(sig.chapter) -> {
                TakeCheckingState(CheckingStatus.VERSE, computeFileChecksum(file))
            }

            else -> null
        }

        val take = Take(
            file.name,
            file,
            takeNumber,
            now,
            null,
            false,
            checkingStatus?.status ?: CheckingStatus.UNCHECKED,
            checkingStatus?.checksum,
            listOf()
        )
        val insertedId = takeRepository.insertForContent(take, chunk).blockingGet()
        take.id = insertedId

        if (isSelected) {
            chunk.selectedTake = take
            contentRepository.update(chunk).blockingAwait()

            val isNarrationMigration = projectMode == ProjectMode.NARRATION && projectAppVersion == ProjectAppVersion.ONE
            // store verse take of incomplete chapter narration to compile later
            if (isNarrationMigration && sig.chapter !in completedChapters && sig.verse != null) {
                val existingFiles = takesToCompile.getOrDefault(sig.chapter, listOf())
                takesToCompile[sig.chapter] = existingFiles.plus(file)
            }
        }
    }

    /** The first take number after [existing]'s whose file name is free next to [target]. */
    private fun nextTakeNumber(existing: List<Take>, target: File): Int {
        var number = (existing.maxOfOrNull { it.number } ?: 0) + 1
        while (target.withTakeNumber(number).exists()) number++
        return number
    }

    /** [this] take file's name with take number [number]: `..._t3.wav` for 3. */
    private fun File.withTakeNumber(number: Int): File =
        resolveSibling(name.replace(TAKE_NUMBER_SUFFIX, "_t$number$2"))

    /** Moves [staged] to [target], or to a free name beside it: never over a file already there. */
    private fun moveIntoProject(staged: File, target: File): File {
        var destination = target
        var n = 2
        while (destination.exists()) destination = target.resolveSibling("${target.nameWithoutExtension}_$n.${target.extension}").also { n++ }
        destination.parentFile?.mkdirs()
        if (!staged.renameTo(destination)) {
            staged.copyTo(destination)
            staged.delete()
        }
        return destination
    }

    private fun createDerivedProjects(
        language: Language,
        sourceCollection: Collection,
        mode: ProjectMode,
        verseByVerse: Boolean
    ): Collection {
        val project = createProjectUseCase.create(
            sourceCollection,
            language,
            mode,
            deriveProjectFromVerses = verseByVerse
        ).doOnError {
            logger.error("Error while deriving project(s) during import", it)
        }.blockingGet()

        // populate all books when importing a project, from the edition the project is on
        createProjectUseCase.createAllBooks(
            sourceCollection.resourceContainer!!.language,
            language,
            mode,
            edition = sourceCollection.resourceContainer
        ).blockingAwait()

        return project
    }

    private fun findSourceCollection(manifestSources: Set<Source>, manifestProject: Project): Collection {
        val allSourceProjects = collectionRepository.getSourceProjects().blockingGet()
        val sourceCollection: Collection? = allSourceProjects
            .asSequence()
            .filter { sourceProject ->
                sourceProject.resourceContainer
                    ?.run { Source(identifier, language.slug, version) }
                    ?.let { it in manifestSources }
                    ?: false
            }
            .filter {
                it.slug == manifestProject.identifier
            }
            .sortedWith(EditionOrder.newestFirstBy { it.resourceContainer })
            .firstOrNull()

        if (sourceCollection == null) {
            logger.error("Failed to find source that matches requested import.")
            throw ImportException(ImportResult.FAILED)
        }
        return sourceCollection
    }

    private fun hasInProgressMarker(resourceContainer: File): Boolean {
        return directoryProvider.newFileReader(resourceContainer).use {
            it.exists(RcConstants.SELECTED_TAKES_FILE)
        }
    }

    /**
     * The source book a backup's project attaches to: the edition its record names, found by
     * fingerprint; for a backup without a record (made by Orature, or before the record existed),
     * the edition embedded in it; and failing both, the installed edition with the version label its
     * manifest names, or the newest, which is logged (S11-Q4).
     */
    private fun backedUpSourceCollection(
        record: BackupEditionRecord?,
        manifestSources: Set<Source>,
        manifestProject: Project,
        fileReader: IFileReader
    ): Collection {
        val edition = runBlocking {
            record?.let { backupEditions.findInstalled(it.edition) }
                ?: embeddedEdition(fileReader, manifestSources)
                ?: manifestSources.firstNotNullOfOrNull { source ->
                    backupEditions.closestInstalled(source.language, source.identifier, source.version)?.also {
                        logger.warn(
                            "Restoring ${manifestProject.identifier}: the backup doesn't say which edition of " +
                                "${source.language}_${source.identifier} it used (its manifest says version " +
                                "${source.version}), so it is attached to v${it.version}, issued ${it.issued}"
                        )
                    }
                }
        }
        val sourceCollection = edition?.let { found ->
            collectionRepository.getSourceProjects().blockingGet()
                .firstOrNull { it.resourceContainer?.id == found.id && it.slug == manifestProject.identifier }
        }
        if (sourceCollection == null) {
            logger.error("Failed to find source that matches requested import.")
            throw ImportException(ImportResult.FAILED)
        }
        return sourceCollection
    }

    /** The installed edition that is the source embedded in the backup, matched by content. */
    private suspend fun embeddedEdition(fileReader: IFileReader, manifestSources: Set<Source>): ResourceMetadata? {
        val embedded = fileReader.list(RcConstants.SOURCE_DIR)
            .filter { OratureFileFormat.isSupported(it.substringAfterLast(".")) }
            .toList()
        for (path in embedded) {
            val file = directoryProvider.createTempFile(File(path).nameWithoutExtension, ".zip")
            try {
                fileReader.stream(path).use { input -> file.outputStream().use { input.copyTo(it) } }
                val dublinCore = ResourceContainer.load(file).use { it.manifest.dublinCore }
                val isProjectSource = manifestSources.any {
                    it.identifier == dublinCore.identifier && it.language == dublinCore.language.identifier
                }
                if (!isProjectSource) continue
                val fingerprint = editionFingerprinter.fingerprint(file)
                backupEditions.findInstalled(dublinCore.language.identifier, dublinCore.identifier, fingerprint)
                    ?.let { return it }
            } catch (e: Exception) {
                logger.error("Could not identify the embedded source $path", e)
            } finally {
                file.delete()
            }
        }
        return null
    }

    /**
     * Gives the chapters that were held back on an upgrade their earlier edition's verses again,
     * before their takes are imported onto them. A chapter whose edition can't be found keeps the
     * book's edition, and its takes are matched to that edition's verses by number.
     */
    private fun restoreHeldChapters(project: Collection, heldBack: List<BackupHeldChapter>) {
        if (heldBack.isEmpty()) return
        runBlocking {
            val editions = heldBack.mapNotNull { held ->
                backupEditions.findInstalled(held.edition)?.let { held.chapter to it }
                    ?: null.also {
                        logger.warn(
                            "Restoring ${project.slug} chapter ${held.chapter}: its edition " +
                                "${held.edition.language}_${held.edition.identifier} v${held.edition.version} " +
                                "isn't installed, so it takes the book's edition"
                        )
                    }
            }.toMap()
            upgradeBookEdition.holdChapters(project.id, editions)
        }
    }

    private fun importSources(fileReader: IFileReader, directory: String = RcConstants.SOURCE_DIR) {
        if (directory != RcConstants.SOURCE_DIR && !fileReader.exists(directory)) return
        val sourceFiles: Sequence<String> = fileReader
            .list(directory)
            .filter {
                val ext = it.substringAfterLast(".")
                OratureFileFormat.isSupported(ext)
            }

        val firstTry: Map<String, ImportResult> = sourceFiles
            .map { importSource(it, fileReader) }
            .toMap()

        // If our first try results contain both an UNMATCHED_HELP and a SUCCESS, then a retry might help.
        if (firstTry.containsValue(ImportResult.SUCCESS)) {
            firstTry
                .filter { (_, result) -> result == ImportResult.UNMATCHED_HELP }
                .forEach { (file, _) -> importSource(file, fileReader) }
        }
    }

    private fun importSource(fileInZip: String, fileReader: IFileReader): Pair<String, ImportResult> {
        val name = File(fileInZip).nameWithoutExtension
        val result = importAsStream(name, fileReader.stream(fileInZip))
            .blockingGet()
        logger.debug("Import source resource container {} result {}", name, result)
        return fileInZip to result
    }

    private fun createTranslation(sourceLanguage: Language, targetLanguage: Language): Translation {
        val translation = Translation(sourceLanguage, targetLanguage, LocalDateTime.now())
        val id = languageRepository
            .insertTranslation(translation)
            .doOnError { e ->
                logger.error("Error in inserting translation", e)
            }
            .onErrorReturnItem(0)
            .blockingGet()
        translation.id = id
        return translation
    }

    private fun getContent(sig: ContentSignature, project: Collection, metadata: ResourceMetadata): Content? {
        return contentCache.computeIfAbsent(sig) { (chapter, verse, sort, type) ->
            val collection: Observable<Collection> = collectionRepository
                .getChildren(project)
                .flattenAsObservable { it }
                .filter { chapterCollection ->
                    chapterCollection.slug.endsWith("_$chapter")
                }

            val metaOrHelpStartVerse = when (type) {
                ContentType.META -> 1
                else -> 0
            }

            val isHelpVerse = metadata.type == ContainerType.Help && verse != null

            val content: Maybe<Content> = collection
                .flatMap {
                    contentRepository.getByCollection(it).flattenAsObservable { it }
                }
                // If we have help resource chunks, filter to linked TEXT chunks
                .filter { if (isHelpVerse) it.type == ContentType.TEXT else true }
                // If we have help resource chunks, fetch resources by linked TEXT chunk
                // and related resource container
                .flatMap { if (isHelpVerse) resourceRepository.getResources(it, metadata) else Observable.just(it) }
                // If type isn't specified in filename, match on TEXT.
                .filter { content -> content.type == (type ?: ContentType.TEXT) }
                // If verse number isn't specified in filename, assume chapter helps or meta.
                .filter { content ->
                    // start is not unique for chunks, as it refers to verse ranges
                    val number = when (content.labelKey) {
                        "chunk" -> content.sort
                        else -> content.start
                    }
                    number == (verse ?: metaOrHelpStartVerse)
                }
                // If sort isn't specified in filename,
                // DON'T filter on it, because we only need it for helps and meta.
                .filter { content -> sort?.let { content.sort == sort } ?: true }
                .firstElement()

            content.blockingGet()
        }
    }

    /**
     * Compile all the selected takes of every verse into a chapter, so that narration
     * can restore the file as one chapter take. Only call this method if the chapter
     * has not completed/compiled before migrating to Orature 3.x.
     */
    private fun compileIncompleteChapterNarration(
        chapterNumber: Int,
        takeFiles: List<File>,
        project: Collection,
        metadata: ResourceMetadata
    ) {
         val collections = collectionRepository
            .getChildren(project)
            .blockingGet()

        val chapterCollection = collections.find {
            collection -> collection.slug.endsWith("_$chapterNumber")
        } ?: return

        val chapterContent = contentRepository.getCollectionMetaContent(chapterCollection).blockingGet()
        val chunkCount = contentRepository.getByCollection(chapterCollection).blockingGet()
            .count { content -> content.type == ContentType.TEXT }

        val fileNamer = FileNamer(
            start = null,
            end = null,
            sort = chapterNumber,
            contentType = ContentType.META,
            languageSlug = metadata.language.slug,
            bookSlug = project.slug,
            rcSlug = metadata.identifier,
            chunkCount = chunkCount.toLong(),
            chapterCount = collections.count().toLong(),
            chapterTitle = "$chapterNumber",
            chapterSort = chapterNumber
        )
        val fileName = fileNamer.generateName(1, AudioFileFormat.WAV)
        val filesToCompile = takeFiles.sortedBy { parseNumbers(it.name)!!.contentSignature.verse } // sort by verse order
        val compiled = concatAudioUseCase.execute(filesToCompile, includeMarkers = true).blockingGet()
        val chapterFile = takeFiles.first().parentFile.resolve(fileName)
            .apply {
                createNewFile()
                compiled.copyTo(this, overwrite = true)
                compiled.delete()
            }

        val chapterTake = Take(
            fileName,
            chapterFile,
            number = 1,
            LocalDate.now(),
            null,
            false,
            CheckingStatus.UNCHECKED,
            checksum = null,
            listOf()
        )
        val insertedId = takeRepository.insertForContent(chapterTake, chapterContent).blockingGet()
        chapterTake.id = insertedId
        chapterContent.selectedTake = chapterTake
        contentRepository.update(chapterContent).blockingAwait()
    }

    private fun parseNumbers(filename: String): TakeSignature? {
        val matcher = takeFilenamePattern.matcher(filename)
        return if (matcher.find()) {
            val chapter = matcher.group(1).toInt()
            val verse = matcher.group(2)?.toIntOrNull()
            val sort = matcher.group(3)?.toIntOrNull()
            val type = matcher.group(4)?.let { ContentType.of(it) }
            val take = matcher.group(5).toInt()
            TakeSignature(ContentSignature(chapter, verse, sort, type), take)
        } else {
            null
        }
    }

    private companion object {
        /** The take number at the end of a take file's name: `_t12.wav`. */
        val TAKE_NUMBER_SUFFIX = Regex("""_t(\d+)(\.[^.]+)$""")
    }

    data class ContentSignature(val chapter: Int, val verse: Int?, val sort: Int?, val type: ContentType?)
    data class TakeSignature(val contentSignature: ContentSignature, val take: Int)
}
