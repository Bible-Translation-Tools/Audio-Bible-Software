package org.bibletranslationtools.bttrecorder2.legacymigration

/**
 * What the migration will do with every legacy project on the device: the projects it will create,
 * and what happens to each legacy project.
 */
data class LegacyMigrationPlan(
    val projects: List<MigrationProject>,
    val legacyProjects: List<LegacyProjectPlan>
)

/** Identifies a project the migration creates: one book, in one target language, on one template. */
data class MigrationProjectKey(val targetLanguage: String, val book: String, val template: UlbTemplate)

/**
 * A project the migration will create. Several legacy projects can feed one: the old app kept one
 * per version label (ulb, udb, reg) for the same book and language.
 */
data class MigrationProject(
    val key: MigrationProjectKey,
    val book: LegacyBook,
    /** Merged from every legacy project feeding it, without duplicates. */
    val contributors: List<String>,
    /** The legacy projects feeding it, by id. */
    val legacyProjectIds: List<Int>,
    /** Chapters with at least one chapter take to build, in order. */
    val chapters: List<ChapterPlan>
)

/** One chapter's chapter takes: one per legacy project that recorded something in it. */
data class ChapterPlan(val number: Int, val takes: List<ChapterTakePlan>) {
    /** The take that becomes the chapter's selected take. */
    val selected: ChapterTakePlan get() = takes.single { it.selected }
}

/**
 * One chapter take to build: a legacy project's selected takes for the chapter, in verse order.
 * Verses with nothing usable are simply absent.
 */
data class ChapterTakePlan(
    val legacyProjectId: Int,
    val versionLabel: String,
    val selected: Boolean,
    val pieces: List<ChapterPiece>
) {
    /** How many verses it covers. */
    val verseCount: Int get() = pieces.sumOf { it.verses.last - it.verses.first + 1 }
}

/** One verse or chunk of a chapter take: [take]'s audio, marked as [verses]. */
data class ChapterPiece(val verses: IntRange, val take: LegacyTake)

/** What happens to one legacy project, and everything worth reporting about it. */
data class LegacyProjectPlan(
    val legacyProject: LegacyProject,
    val disposition: Disposition,
    val problems: List<MigrationProblem>
) {
    sealed interface Disposition {
        /** Its recordings go into [into]. */
        data class Migrated(val into: MigrationProjectKey) : Disposition

        /** It holds no recordings, so there's nothing to carry over. */
        data object NothingRecorded : Disposition

        /** It isn't migrated at all; [problems] say why. It stays in the old app's storage. */
        data object Kept : Disposition
    }

    /** Whether its old files may be deleted once its project is verified. */
    val deletableOnceVerified: Boolean
        get() = when (disposition) {
            is Disposition.Migrated -> problems.none { it.blocksDeletion }
            Disposition.NothingRecorded -> true
            Disposition.Kept -> false
        }
}

/**
 * Something the plan found. [blocksDeletion] says whether it keeps the legacy project's files from
 * being deleted: true whenever recorded audio would otherwise be lost.
 */
sealed interface MigrationProblem {
    val blocksDeletion: Boolean

    // ── the whole project stays behind ─────────────────────────────────────────────────────

    /** An OBS, tQ or other non-Bible project, which the migration doesn't handle (M3-4). */
    data class OutOfScope(val anthology: String) : MigrationProblem {
        override val blocksDeletion = true
    }

    /** An English project labelled `udb`, which would need a UDB template (M5-6). */
    data object EnglishUdb : MigrationProblem {
        override val blocksDeletion = true
    }

    /** No template has this book. */
    data class BookNotInTemplate(val book: String) : MigrationProblem {
        override val blocksDeletion = true
    }

    /** What the project's mode, its takes' metadata and its filenames say about its division disagree. */
    data class ChunkingContradicted(val detail: String) : MigrationProblem {
        override val blocksDeletion = true
    }

    // ── a verse or chunk is left out; the rest is migrated ─────────────────────────────────

    /** The take that would be used has no file. */
    data class TakeFileMissing(val chapter: Int, val startVerse: Int, val take: LegacyTake) : MigrationProblem {
        override val blocksDeletion = true
    }

    /** Neither the take's filename nor its metadata says which verses it holds. */
    data class RangeUnknown(val chapter: Int, val startVerse: Int, val take: LegacyTake) : MigrationProblem {
        override val blocksDeletion = true
    }

    /** The take's filename, its metadata or its chunk row disagree about which verses it holds. */
    data class RangeDisagrees(val chapter: Int, val startVerse: Int, val take: LegacyTake, val detail: String) :
        MigrationProblem {
        override val blocksDeletion = true
    }

    /** The template has no verse or chunk with exactly this range. It is never relabelled to fit. */
    data class RangeNotInTemplate(val chapter: Int, val verses: IntRange, val template: UlbTemplate) : MigrationProblem {
        override val blocksDeletion = true
    }

    // ── reported, nothing lost ─────────────────────────────────────────────────────────────

    /** No usable selection, so the old app's rule chose a take: highest rating, then newest (M5-3). */
    data class SelectionChosen(val chapter: Int, val startVerse: Int, val take: LegacyTake) : MigrationProblem {
        override val blocksDeletion = false
    }

    /** The project had no mode, so its division was worked out from its takes; [how] says how. */
    data class ChunkingInferred(val chunking: LegacyChunking, val how: String) : MigrationProblem {
        override val blocksDeletion = false
    }

    /** It was combined with projects that named a different source language (templates are English). */
    data class SourceLanguageCombined(val sourceLanguages: Set<String>) : MigrationProblem {
        override val blocksDeletion = false
    }
}
