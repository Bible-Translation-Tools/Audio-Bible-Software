package org.bibletranslationtools.bttrecorder2.legacymigration

import org.bibletranslationtools.bttrecorder2.legacymigration.LegacyProjectPlan.Disposition
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.BookNotInTemplate
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.ChunkingContradicted
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.ChunkingInferred
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.EnglishUdb
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.OutOfScope
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.RangeDisagrees
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.RangeNotInTemplate
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.RangeUnknown
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.SelectionChosen
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.SourceLanguageCombined
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.TakeFileMissing

/**
 * Decides what every legacy project becomes: which template it's recorded against, which take of
 * each verse or chunk goes into each chapter take, and what stays behind and why.
 *
 * Pure logic over what the reader reported and the installed templates' chunks: it reads no files
 * and writes nothing.
 */
class PlanLegacyMigration {

    /**
     * @param enabled the templates this version migrates onto. A project that belongs on another is
     *   [Disposition.Deferred]: left as it is for a later version.
     */
    fun execute(
        projects: List<LegacyProject>,
        templates: TemplateChunks,
        enabled: Set<UlbTemplate> = UlbTemplate.entries.toSet()
    ): LegacyMigrationPlan {
        val assessed = projects.map { assess(it, templates, enabled) }
        val groups = assessed.filter { it.key != null }.groupBy { it.key!! }

        val migrationProjects = groups.map { (key, members) -> buildProject(key, members) }
            .sortedWith(compareBy({ it.key.targetLanguage }, { it.book.number }, { it.key.template }))

        val legacyPlans = assessed.map { assessment ->
            val combined = assessment.key?.let { key ->
                groups.getValue(key).mapNotNull { it.project.sourceLanguage }.toSet().takeIf { it.size > 1 }
            }
            LegacyProjectPlan(
                legacyProject = assessment.project,
                disposition = assessment.disposition,
                problems = assessment.problems + listOfNotNull(combined?.let(::SourceLanguageCombined))
            )
        }
        return LegacyMigrationPlan(migrationProjects, legacyPlans)
    }

    // ── one legacy project ───────────────────────────────────────────────────────────────────

    private class Assessment(
        val project: LegacyProject,
        val disposition: Disposition,
        val problems: List<MigrationProblem>,
        /** Chapter number to its pieces, in verse order. */
        val pieces: Map<Int, List<ChapterPiece>> = emptyMap()
    ) {
        val key: MigrationProjectKey? get() = (disposition as? Disposition.Migrated)?.into
    }

    /** A verse or chunk's chosen take and the verses it holds, before matching the template. */
    private data class Candidate(val chapter: Int, val verses: IntRange, val take: LegacyTake)

    private fun assess(project: LegacyProject, templates: TemplateChunks, enabled: Set<UlbTemplate>): Assessment {
        if (project.anthology !in BIBLE_ANTHOLOGIES) return kept(project, OutOfScope(project.anthology))
        if (project.targetLanguage == ENGLISH && project.versionLabel == UDB) return kept(project, EnglishUdb)

        val problems = mutableListOf<MigrationProblem>()
        val candidates = project.chapters.flatMap { chapter ->
            chapter.chunks.mapNotNull { chunk -> candidate(chapter.number, chunk, problems) }
        }
        val hasRecordings = project.chapters.any { chapter -> chapter.chunks.any { it.takes.isNotEmpty() } }
        if (!hasRecordings) return Assessment(project, Disposition.NothingRecorded, problems)

        val chunking = when (val resolved = resolveChunking(project, candidates, templates)) {
            is Resolved.Known -> resolved.chunking
            is Resolved.Inferred -> resolved.chunking.also { problems += ChunkingInferred(it, resolved.how) }
            is Resolved.Contradicted -> return kept(project, problems + ChunkingContradicted(resolved.detail))
        }
        val template = templateFor(chunking)
        if (template !in enabled) return Assessment(project, Disposition.Deferred(template), problems)
        if (!templates.hasBook(template, project.book.slug)) {
            return kept(project, problems + BookNotInTemplate(project.book.slug))
        }

        val pieces = candidates.mapNotNull { candidate ->
            val chunks = templates.chapter(template, project.book.slug, candidate.chapter)
            if (chunks == null || candidate.verses !in chunks) {
                problems += RangeNotInTemplate(candidate.chapter, candidate.verses, template)
                null
            } else {
                candidate
            }
        }.groupBy({ it.chapter }, { ChapterPiece(it.verses, it.take) })
            .mapValues { (_, chapterPieces) -> chapterPieces.sortedBy { it.verses.first } }

        if (pieces.isEmpty()) return kept(project, problems)
        val key = MigrationProjectKey(project.targetLanguage, project.book.slug, template)
        return Assessment(project, Disposition.Migrated(key), problems, pieces)
    }

    private fun kept(project: LegacyProject, problem: MigrationProblem) = kept(project, listOf(problem))

    private fun kept(project: LegacyProject, problems: List<MigrationProblem>) =
        Assessment(project, Disposition.Kept, problems)

    /**
     * The take that represents [chunk] and the verses it holds, or null when nothing usable is
     * there. A chunk with no takes adds nothing and reports nothing.
     */
    private fun candidate(chapter: Int, chunk: LegacyChunk, problems: MutableList<MigrationProblem>): Candidate? {
        if (chunk.takes.isEmpty()) return null
        val take = chunk.takes.firstOrNull { it.id == chunk.selectedTakeId }
            ?: oldAppSelection(chunk.takes).also { problems += SelectionChosen(chapter, chunk.startVerse, it) }

        val fromName = take.verses
        val fromMetadata = take.metadata?.verses
        val verses = when {
            fromName == null && fromMetadata == null -> {
                problems += RangeUnknown(chapter, chunk.startVerse, take)
                return null
            }
            fromName != null && fromMetadata != null && fromName != fromMetadata -> {
                problems += RangeDisagrees(chapter, chunk.startVerse, take, "filename $fromName, metadata $fromMetadata")
                return null
            }
            else -> fromName ?: fromMetadata!!
        }
        if (verses.first != chunk.startVerse) {
            problems += RangeDisagrees(chapter, chunk.startVerse, take, "the take holds $verses")
            return null
        }
        if (!take.onDisk) {
            problems += TakeFileMissing(chapter, chunk.startVerse, take)
            return null
        }
        return Candidate(chapter, verses, take)
    }

    /** The old app's own rule, applied every time it saved a take: highest rating, then newest. */
    private fun oldAppSelection(takes: List<LegacyTake>): LegacyTake =
        takes.maxWith(compareBy<LegacyTake>({ it.rating }, { it.recordedAt ?: Long.MIN_VALUE }, { it.number }))

    // ── how the project was divided ──────────────────────────────────────────────────────────

    private sealed interface Resolved {
        data class Known(val chunking: LegacyChunking) : Resolved
        data class Inferred(val chunking: LegacyChunking, val how: String) : Resolved
        data class Contradicted(val detail: String) : Resolved
    }

    /**
     * The project's mode when it has one; it also decides how verses not yet recorded are divided.
     * Only without one, in order (M2-1): the mode written into its takes, then whether any take spans
     * several verses, then which template fits its recorded ranges better.
     */
    private fun resolveChunking(
        project: LegacyProject,
        candidates: List<Candidate>,
        templates: TemplateChunks
    ): Resolved {
        val spansSeveral = candidates.firstOrNull { it.verses.first != it.verses.last }
        project.chunking?.let { mode ->
            if (mode == LegacyChunking.VERSE_BY_VERSE && spansSeveral != null) {
                return Resolved.Contradicted(
                    "recorded verse by verse, but chapter ${spansSeveral.chapter} has a take of ${spansSeveral.verses}"
                )
            }
            return Resolved.Known(mode)
        }

        val fromTakes = project.chapters.flatMap { it.chunks }.flatMap { it.takes }
            .mapNotNull { it.metadata?.chunking }.toSet()
        when {
            fromTakes.size > 1 -> return Resolved.Contradicted("its takes were recorded in both modes")
            fromTakes.size == 1 -> {
                val mode = fromTakes.single()
                if (mode == LegacyChunking.VERSE_BY_VERSE && spansSeveral != null) {
                    return Resolved.Contradicted(
                        "its takes say verse by verse, but chapter ${spansSeveral.chapter} has a take of ${spansSeveral.verses}"
                    )
                }
                return Resolved.Inferred(mode, "from the mode written into its takes")
            }
        }

        if (spansSeveral != null) {
            return Resolved.Inferred(LegacyChunking.CHUNKS, "a take spans several verses")
        }

        // Every recorded take is a single verse. Whichever template has more of them as units of
        // their own fits better; when both fit equally, nothing tells them apart.
        fun unmatched(template: UlbTemplate) = candidates.count { candidate ->
            templates.chapter(template, project.book.slug, candidate.chapter)?.contains(candidate.verses) != true
        }
        val verses = unmatched(UlbTemplate.VERSES)
        val chunks = unmatched(UlbTemplate.CHUNKS)
        return if (chunks < verses) {
            Resolved.Inferred(LegacyChunking.CHUNKS, "its recorded verses fit the chunk template better")
        } else {
            Resolved.Inferred(
                LegacyChunking.VERSE_BY_VERSE,
                if (verses < chunks) "its recorded verses fit the verse template better"
                else "its recorded verses fit both templates; assumed verse by verse"
            )
        }
    }

    private fun templateFor(chunking: LegacyChunking) = when (chunking) {
        LegacyChunking.VERSE_BY_VERSE -> UlbTemplate.VERSES
        LegacyChunking.CHUNKS -> UlbTemplate.CHUNKS
    }

    // ── one project to create ────────────────────────────────────────────────────────────────

    private fun buildProject(key: MigrationProjectKey, members: List<Assessment>): MigrationProject {
        val labelPriority = labelPriority(key.targetLanguage)
        val ordered = members.sortedBy { member ->
            labelPriority.indexOf(member.project.versionLabel).takeIf { it >= 0 } ?: labelPriority.size
        }
        val chapters = ordered.flatMap { it.pieces.keys }.toSortedSet().map { chapter ->
            val takes = ordered.mapNotNull { member ->
                member.pieces[chapter]?.let { pieces ->
                    ChapterTakePlan(member.project.id, member.project.versionLabel, selected = false, pieces = pieces)
                }
            }
            // The most complete one is selected; ties go to the earliest in label priority.
            val best = takes.withIndex()
                .maxWith(compareBy<IndexedValue<ChapterTakePlan>>({ it.value.verseCount }, { -it.index }))
                .index
            ChapterPlan(chapter, takes.mapIndexed { index, take -> take.copy(selected = index == best) })
        }
        return MigrationProject(
            key = key,
            book = ordered.first().project.book,
            contributors = ordered.flatMap { contributorsOf(it.project) }.distinct(),
            legacyProjectIds = ordered.map { it.project.id },
            chapters = chapters
        )
    }

    /**
     * Ties go first to the label matching the identifier the app gives the project, then ulb, udb,
     * reg. The app uses the source's identifier (`ulb`) when the target language is the source's
     * (English), and `reg` otherwise.
     */
    private fun labelPriority(targetLanguage: String): List<String> =
        (listOf(if (targetLanguage == ENGLISH) ULB else REG) + listOf(ULB, UDB, REG)).distinct()

    private fun contributorsOf(project: LegacyProject): List<String> =
        project.contributors.orEmpty().split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        val BIBLE_ANTHOLOGIES = setOf("ot", "nt")
        const val ENGLISH = "en"
        const val ULB = "ulb"
        const val UDB = "udb"
        const val REG = "reg"
    }
}
