package org.bibletranslationtools.bttrecorder2.legacymigration

import io.reactivex.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.bttrecorder2.legacymigration.WriteMigrationBackup.ChapterTakeOutcome
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.importer.ImportCallbackParameter
import org.bibletranslationtools.otter.common.domain.project.importer.ImportOptions
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.utils.computeFileChecksum
import java.io.File

/**
 * Imports a migration backup through the app's own import, then proves that every chapter take
 * landed (M8). That proof is what allows a legacy project to be deleted. The import alone isn't
 * enough: it can drop a take, or register it unselected, and still report success.
 *
 * Import merges a backup into a project of the same book and target language already here, and
 * that project keeps its edition. So a project already here is only imported into when that is
 * safe (M8-1): it is on the template's edition, and the only recordings it holds are this
 * migration's. That is the case for an empty project an earlier import created for the language's
 * other books, and for this project's own earlier, interrupted migration, whose takes the import
 * recognises and doesn't add again. Anything else is refused, and the legacy project is kept.
 */
class ImportMigrationBackup(
    private val importProjectUseCase: ImportProjectUseCase,
    private val collectionRepository: ICollectionRepository,
    private val contentRepository: IContentRepository,
    private val takeRepository: ITakeRepository
) {
    sealed interface Outcome {
        /**
         * Every written chapter take is in [projectBook], which is on the template's edition, and
         * each chapter's selected take is selected. The import [added] that many takes, and found
         * [alreadyThere] that many already in the project.
         */
        data class Verified(val projectBook: Collection, val added: Int, val alreadyThere: Int) : Outcome

        /** Not imported: a project of this book and language is already here, and importing could harm it. */
        data class Refused(val reason: RefusalReason) : Outcome

        /** The import itself didn't succeed. */
        data class ImportFailed(val result: ImportResult) : Outcome

        /** Imported, but not everything is where it should be. */
        data class NotVerified(val failures: List<VerificationFailure>) : Outcome
    }

    sealed interface RefusalReason {
        /** It is on another edition, which it would keep, so the chapter takes wouldn't fit its verses. */
        data class OnAnotherEdition(val projectBook: Collection, val editionId: Int?) : RefusalReason

        /** It holds [count] recordings that aren't this migration's. */
        data class HoldsOtherRecordings(val projectBook: Collection, val count: Int) : RefusalReason
    }

    sealed interface VerificationFailure {
        data object ProjectMissing : VerificationFailure
        data class WrongEdition(val editionId: Int?) : VerificationFailure
        data class ChapterMissing(val chapter: Int) : VerificationFailure
        data class TakeMissing(val take: ChapterTakeOutcome.Written) : VerificationFailure
        data class TakeNotSelected(val take: ChapterTakeOutcome.Written) : VerificationFailure
    }

    /**
     * Imports [backup], written for [project] on [edition], and verifies it. [backup] must have a
     * backup file: with nothing usable there is nothing to import.
     */
    suspend fun execute(
        project: MigrationProject,
        edition: ResourceMetadata,
        backup: WriteMigrationBackup.Result
    ): Outcome = withContext(Dispatchers.IO) {
        val file = requireNotNull(backup.backup) { "there is no backup to import for ${project.key}" }
        val written = backup.takes.filterIsInstance<ChapterTakeOutcome.Written>()
        val checksums = Checksums()

        matchingProject(project.key, edition)?.let { existing ->
            refusalFor(existing, edition, written.map { it.checksum }.toSet(), checksums)?.let {
                return@withContext Outcome.Refused(it)
            }
        }

        val counts = TakeCounts()
        val result = importProjectUseCase.import(file, counts, null).await()
        if (result != ImportResult.SUCCESS) return@withContext Outcome.ImportFailed(result)

        val projectBook = matchingProject(project.key, edition)
            ?: return@withContext Outcome.NotVerified(listOf(VerificationFailure.ProjectMissing))
        val failures = verify(projectBook, edition, written, checksums)
        if (failures.isEmpty()) Outcome.Verified(projectBook, counts.added, counts.alreadyThere)
        else Outcome.NotVerified(failures)
    }

    /**
     * The project the import would merge into: this book in this target language, translated from
     * the template's source (any edition of it), as the import matches them.
     */
    private suspend fun matchingProject(key: MigrationProjectKey, edition: ResourceMetadata): Collection? =
        collectionRepository.getDerivedProjectsSuspend()
            .filter { it.slug == key.book && it.resourceContainer?.language?.slug == key.targetLanguage }
            .firstOrNull { book ->
                collectionRepository.getSourceSuspend(book)?.resourceContainer?.let {
                    it.language.slug == edition.language.slug && it.identifier == edition.identifier
                } == true
            }

    private suspend fun refusalFor(
        existing: Collection,
        edition: ResourceMetadata,
        ours: Set<String>,
        checksums: Checksums
    ): RefusalReason? {
        val editionId = editionOf(existing)
        if (editionId != edition.id) return RefusalReason.OnAnotherEdition(existing, editionId)
        val others = collectionRepository.getChildrenSuspend(existing)
            .flatMap { takeRepository.getByCollectionSuspend(it) }
            .count { checksums.of(it.path) !in ours }
        return if (others > 0) RefusalReason.HoldsOtherRecordings(existing, others) else null
    }

    private suspend fun verify(
        projectBook: Collection,
        edition: ResourceMetadata,
        written: List<ChapterTakeOutcome.Written>,
        checksums: Checksums
    ): List<VerificationFailure> {
        val editionId = editionOf(projectBook)
        if (editionId != edition.id) return listOf(VerificationFailure.WrongEdition(editionId))
        val chapters = collectionRepository.getChildrenSuspend(projectBook).associateBy { it.sort }
        return written.groupBy { it.chapter }.flatMap { (number, takes) ->
            val chapter = chapters[number] ?: return@flatMap listOf(VerificationFailure.ChapterMissing(number))
            val meta = contentRepository.getCollectionMetaContentSuspend(chapter)
            val landed = takeRepository.getByContentSuspend(meta).map { checksums.of(it.path) }.toSet()
            val selected = meta.selectedTake?.let { checksums.of(it.path) }
            takes.mapNotNull { take ->
                when {
                    take.checksum !in landed -> VerificationFailure.TakeMissing(take)
                    take.selected && selected != take.checksum -> VerificationFailure.TakeNotSelected(take)
                    else -> null
                }
            }
        }
    }

    private suspend fun editionOf(projectBook: Collection): Int? =
        collectionRepository.getSourceSuspend(projectBook)?.resourceContainer?.id

    /** File checksums, as the import compares takes, each file read once. */
    private class Checksums {
        private val known = mutableMapOf<File, String?>()
        fun of(file: File): String? = known.getOrPut(file) { computeFileChecksum(file) }
    }

    /** Hears how many takes the import added; the migration never asks the user anything. */
    private class TakeCounts : ProjectImporterCallback {
        var added = 0
        var alreadyThere = 0

        override fun onNotifyTakesImported(merged: Boolean, added: Int, alreadyThere: Int) {
            this.added = added
            this.alreadyThere = alreadyThere
        }

        override fun onRequestUserInput(): Single<ImportOptions> = Single.just(ImportOptions())
        override fun onRequestUserInput(parameter: ImportCallbackParameter): Single<ImportOptions> =
            Single.just(ImportOptions())
        override fun onNotifyProgress(localizeKey: String?, message: String?, percent: Double?) = Unit
        override fun onNotifySuccess(language: String?, project: String?, workbookDescriptor: WorkbookDescriptor?) = Unit
        override fun onError(filePath: String) = Unit
    }
}
