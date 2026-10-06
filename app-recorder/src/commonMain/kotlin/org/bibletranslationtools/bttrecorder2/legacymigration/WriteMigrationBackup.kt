package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.project.WriteChapterTakesBackup
import org.bibletranslationtools.otter.common.utils.computeFileChecksum
import java.io.File

/**
 * Writes the backup a [MigrationProject] is imported from (M7). Each of the project's
 * [ChapterTakePlan]s is compiled into one chapter take, and the backup is bound to the edition its
 * template was installed as. It is an ordinary backup, so the ordinary import restores it.
 *
 * The project is a dialect project: its chapters are recorded in the language they are read in,
 * and it isn't chunked, so the recordist's divisions live on as verse markers in the chapter takes.
 */
class WriteMigrationBackup(
    private val compile: CompileLegacyChapterTake,
    private val writeBackup: WriteChapterTakesBackup,
    private val templateFiles: IUlbTemplateFiles,
    private val tempFiles: ITempFileProvider
) {
    data class Result(
        /** The backup, or null when no chapter take could be compiled, so there is nothing to import. */
        val backup: File?,
        /** One per chapter take the project planned, in chapter order. */
        val takes: List<ChapterTakeOutcome>
    )

    sealed interface ChapterTakeOutcome {
        val chapter: Int
        val plan: ChapterTakePlan
        val problems: List<MigrationProblem>

        /**
         * It is in the backup with these [markers]; [selected] says whether it is the chapter's
         * selected take. [checksum] is its file's, as the import compares takes, so it can be found
         * again once imported.
         */
        data class Written(
            override val chapter: Int,
            override val plan: ChapterTakePlan,
            val selected: Boolean,
            val totalFrames: Int,
            val markers: List<VerseMarker>,
            val checksum: String,
            override val problems: List<MigrationProblem>
        ) : ChapterTakeOutcome

        /** None of its takes could be used, so it isn't in the backup. */
        data class LeftOut(
            override val chapter: Int,
            override val plan: ChapterTakePlan,
            override val problems: List<MigrationProblem>
        ) : ChapterTakeOutcome
    }

    /**
     * Writes [project]'s backup to [destination], replacing it. [edition] must be the edition that
     * [project]'s template is installed as, and [targetLanguage] the project's target language.
     */
    suspend fun execute(
        project: MigrationProject,
        edition: ResourceMetadata,
        targetLanguage: Language,
        destination: File
    ): Result = withContext(Dispatchers.IO) {
        val compiled = mutableListOf<File>()
        val editionFile = tempFiles.createTempFile("legacy-template-${project.key.template.name.lowercase()}", ".zip")
        try {
            val outcomes = project.chapters.flatMap { chapter ->
                val results = chapter.takes.map { plan ->
                    val file = tempFiles.createTempFile("legacy-c${chapter.number}-", ".wav").also { compiled += it }
                    Triple(plan, file, compile.execute(chapter.number, plan, file))
                }
                val selected = selectedAmong(chapter, results.filter { it.third is CompileLegacyChapterTake.Result.Compiled }.map { it.first })
                results.map { (plan, file, result) ->
                    when (result) {
                        is CompileLegacyChapterTake.Result.Compiled -> ChapterTakeOutcome.Written(
                            chapter.number, plan, plan === selected, result.totalFrames, result.markers,
                            checksumOf(file), result.problems
                        ) to file
                        is CompileLegacyChapterTake.Result.NothingUsable ->
                            ChapterTakeOutcome.LeftOut(chapter.number, plan, result.problems) to null
                    }
                }
            }

            val written = outcomes.mapNotNull { (outcome, file) ->
                (outcome as? ChapterTakeOutcome.Written)?.let { WriteChapterTakesBackup.ChapterTake(it.chapter, file!!, it.selected) }
            }
            if (written.isEmpty()) {
                destination.delete()
                return@withContext Result(null, outcomes.map { it.first })
            }

            templateFiles.open(project.key.template).use { input -> editionFile.outputStream().use { input.copyTo(it) } }
            writeBackup.execute(
                destination,
                edition,
                editionFile,
                project.key.book,
                targetLanguage,
                ProjectMode.DIALECT,
                project.contributors,
                written
            )
            Result(destination, outcomes.map { it.first })
        } finally {
            compiled.forEach { it.delete() }
            editionFile.delete()
        }
    }

    /**
     * The chapter take to select among those that compiled: the one the plan selected, or, when it
     * couldn't be compiled, the next most complete, earliest planned first.
     */
    private fun selectedAmong(chapter: ChapterPlan, compiled: List<ChapterTakePlan>): ChapterTakePlan? =
        chapter.selected.takeIf { selected -> compiled.any { it === selected } } ?: compiled.maxByOrNull { it.verseCount }

    private fun checksumOf(file: File): String =
        computeFileChecksum(file) ?: error("the compiled chapter take $file is gone")
}
