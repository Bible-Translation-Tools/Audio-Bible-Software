package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.importer.NewSourceImporter
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Installs the [UlbTemplate]s that legacy projects need, and finds each one's installed edition so
 * the projects can be bound to it.
 *
 * Each template is installed whole, never trimmed to the books in use: an edition is identified by
 * a fingerprint over all its chapters, so a trimmed template would install as a new edition every
 * time. It goes in beside every other installed edition, through [NewSourceImporter].
 *
 * A template is found by content, not by its labels. The verse template's title, version and dates
 * are the same as an older bundled ULB's, so only the fingerprint tells them apart.
 */
class InstallUlbTemplates(
    private val templateFiles: IUlbTemplateFiles,
    private val newSourceImporter: NewSourceImporter,
    private val importProjectUseCase: ImportProjectUseCase,
    private val tempFiles: ITempFileProvider
) {
    private val logger = LoggerFactory.getLogger(InstallUlbTemplates::class.java)

    /** What happened to one template. */
    sealed interface Outcome {
        val template: UlbTemplate

        /** [edition] is the template's installed edition. [newlyInstalled] is false when it was already there. */
        data class Installed(
            override val template: UlbTemplate,
            val edition: ResourceMetadata,
            val newlyInstalled: Boolean
        ) : Outcome

        /** The template couldn't be installed. Worth retrying on a later run. */
        data class NotInstalled(override val template: UlbTemplate, val reason: String) : Outcome
    }

    /** Installs each of [templates] that isn't installed yet, and reports every one. */
    suspend fun execute(templates: Set<UlbTemplate>): Map<UlbTemplate, Outcome> =
        templates.associateWith { install(it) }

    private suspend fun install(template: UlbTemplate): Outcome {
        val copy = try {
            copyOut(template)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Could not read the $template template", e)
            return Outcome.NotInstalled(template, "the template file couldn't be read: ${e.message}")
        }
        return try {
            installFrom(template, copy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Could not install the $template template", e)
            Outcome.NotInstalled(template, e.message ?: e::class.simpleName.orEmpty())
        } finally {
            copy.delete()
        }
    }

    private suspend fun installFrom(template: UlbTemplate, copy: File): Outcome {
        importProjectUseCase.installedEditionOf(copy)?.let { installed ->
            logger.info("The $template template is already installed as edition ${installed.id}")
            return Outcome.Installed(template, installed, newlyInstalled = false)
        }

        val result = newSourceImporter.import(copy).await()
        if (result != ImportResult.SUCCESS && result != ImportResult.ALREADY_EXISTS) {
            return Outcome.NotInstalled(template, "the import returned $result")
        }
        val edition = importProjectUseCase.installedEditionOf(copy)
            ?: return Outcome.NotInstalled(template, "the import returned $result, but no matching edition is installed")
        logger.info("Installed the $template template as edition ${edition.id}")
        return Outcome.Installed(template, edition, newlyInstalled = result == ImportResult.SUCCESS)
    }

    /** The importer reads files, so the template is copied out of wherever it ships first. */
    private suspend fun copyOut(template: UlbTemplate): File = withContext(Dispatchers.IO) {
        val copy = tempFiles.createTempFile("legacy-template-${template.name.lowercase()}", ".zip")
        try {
            templateFiles.open(template).use { input -> copy.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
        copy
    }
}
