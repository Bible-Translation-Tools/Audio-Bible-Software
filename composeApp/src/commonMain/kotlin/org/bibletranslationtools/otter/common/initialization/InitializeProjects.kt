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
@file:Suppress("FunctionNaming")
package org.bibletranslationtools.otter.common.initialization

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import java.io.File
import javax.inject.Inject

class InitializeProjects @Inject constructor(
    private val resourceMetadataRepo: IResourceMetadataRepository,
    private val takeRepo: ITakeRepository,
    private val directoryProvider: IDirectoryProvider,
    private val installedEntityRepo: IInstalledEntityRepository,
    private val workbookRepository: IWorkbookRepository,
    private val rcImporterFactory: RCImporterFactory
) : Installable {
    override val name = "PROJECTS"
    override val version = 2

    private val log = LoggerFactory.getLogger(InitializeProjects::class.java)
    private lateinit var callback: ProjectImporterCallback

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable {
        return Completable.fromCallable {
            var installedVersion = installedEntityRepo.getInstalledVersion(this)
            if (installedVersion != version) {
                log.info("Initializing $name version: $version...")
                progressEmitter.onNext(ProgressStatus(titleKey = "initializingProjects"))

                migrate()

                installedEntityRepo.install(this)
                log.info("$name version: $version installed!")
            } else {
                log.info("$name up to date with version: $version")
            }

            if (fetchProjects().isEmpty()) {
                log.info("Importing projects...")
                callback = setupImportCallback(progressEmitter)

                val dir = directoryProvider.getUserDataDirectory("/")
                importProjects(dir)
            }
        }
    }

    private fun migrate() {
        migrateToVersion1()
        migrateSourcesToVersion2()
    }

    private fun migrateToVersion1() {
        migrateTakesToVersion1()

        val projects = fetchProjects()
        migrateProjectsToVersion1(projects)
    }

    private fun migrateProjectsToVersion1(workbooks: List<Workbook>) {
        workbooks.forEach { workbook ->
            // Migrate main rc
            migrateProjectToVersion1(workbook.target.resourceMetadata, workbook)

            // Migrate linked resources
            workbook.target.linkedResources.forEach { targetRc ->
                migrateProjectToVersion1(targetRc, workbook)
            }
        }
    }

    private fun migrateProjectToVersion1(targetMetadata: ResourceMetadata, workbook: Workbook) {
        val projectFilesAccessor = ProjectFilesAccessor(
            directoryProvider,
            workbook.source.resourceMetadata,
            targetMetadata,
            workbook.target.toCollection()
        )
        val linkedResource = workbook.source.linkedResources
            .firstOrNull { it.identifier == targetMetadata.identifier }

        val projectIsBook = targetMetadata.identifier == workbook.target.resourceMetadata.identifier

        projectFilesAccessor.initializeResourceContainerInDir()
        projectFilesAccessor.copySourceFiles(linkedResource)
        projectFilesAccessor.writeSelectedTakesFile(workbook, projectIsBook)
    }

    private fun migrateSourcesToVersion2() {
        resourceMetadataRepo.getAllSources().blockingGet()
            .forEach { resourceMetadata ->
                if (resourceMetadata.path.isFile) {
                    val sourceFile = resourceMetadata.path
                    val dirName = "${resourceMetadata.language.slug}_${resourceMetadata.identifier}-source"
                    val targetDir = sourceFile.parentFile.resolve(dirName)
                    if (targetDir.exists() && targetDir.list()?.any() == true) {
                        targetDir.deleteRecursively()
                    }

                    directoryProvider.newFileReader(sourceFile).use { reader ->
                        val entries = reader.list(".").toList()
                        when {
                            entries.size == 1 -> {
                                // root is a directory, copy its content to avoid nested dirs
                                reader.copyDirectory(entries.first(), targetDir)
                            }

                            else -> {
                                reader.copyDirectory("/", targetDir)
                            }
                        }
                    }

                    // Delete old resource container
                    resourceMetadata.path.delete()

                    val updatedRc = resourceMetadata.copy(path = targetDir)
                    resourceMetadataRepo.update(updatedRc).blockingGet()
                }
            }
    }

    private fun migrateTakesToVersion1() {
        takeRepo.getAll().blockingGet()
            .forEach { take ->
                val projectDir = take.path.parentFile.parentFile

                if (projectDir.toString().contains(ProjectFilesAccessor.getTakesDirPath())) {
                    // Perhaps already migrated. Skipping...
                    return@forEach
                }

                val takesDir = projectDir.resolve(ProjectFilesAccessor.getTakesDirPath())
                val chapterDir = takesDir.resolve(take.path.parentFile.name)

                chapterDir.mkdirs()

                val destFile = chapterDir.resolve(take.path.name)
                take.path.renameTo(destFile)

                val updatedTake = take.copy(path = destFile)
                takeRepo.update(updatedTake).blockingGet()

                // Delete empty dir
                take.path.parentFile.delete()
            }
    }

    private fun importProjects(dir: File) {
        if (dir.isFile) return

        dir.listFiles()?.forEach {
            // Find resource containers to import
            if (it.isFile && it.name == "manifest.yaml") {
                importProject(it.parentFile)
            }
            importProjects(it)
        }
    }

    private fun importProject(project: File) {
        rcImporterFactory.makeImporter()
            .import(project, callback).toObservable()
            .doOnError { e ->
                log.error("Error importing ${project.name}.", e)
            }
            .blockingSubscribe {
                log.info("${project.name} imported!")
            }
    }

    private fun fetchProjects(): List<Workbook> {
        return workbookRepository.getProjects()
            .doOnError { e ->
                log.error("Error in loading projects", e)
            }
            .blockingGet()
    }

    private fun createTempFile(name: String, extension: String): File {
        val tempFile = File.createTempFile(name, ".$extension")
        tempFile.deleteOnExit()
        return tempFile
    }
}
