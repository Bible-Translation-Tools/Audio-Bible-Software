package org.bibletranslationtools.bttrecorder2.legacymigration

import org.bibletranslationtools.bttrecorder2.legacymigration.ImportMigrationBackup.RefusalReason
import org.bibletranslationtools.bttrecorder2.legacymigration.ImportMigrationBackup.VerificationFailure

/**
 * The journal's reason codes. They are written down and read back by later versions, and the
 * summary words them, so they are fixed strings rather than class names (which a release build's
 * minifier renames).
 */
object JournalReasons {
    const val OUT_OF_SCOPE = "out_of_scope"
    const val ENGLISH_UDB = "english_udb"
    const val BOOK_NOT_IN_TEMPLATE = "book_not_in_template"
    const val CHUNKING_CONTRADICTED = "chunking_contradicted"
    const val TAKE_FILE_MISSING = "take_file_missing"
    const val RANGE_UNKNOWN = "range_unknown"
    const val RANGE_DISAGREES = "range_disagrees"
    const val RANGE_NOT_IN_TEMPLATE = "range_not_in_template"
    const val AUDIO_UNREADABLE = "audio_unreadable"
    const val AUDIO_FORMAT_UNEXPECTED = "audio_format_unexpected"
    const val AUDIO_REPAIRED = "audio_repaired"
    const val INNER_MARKERS_SET_ASIDE = "inner_markers_set_aside"
    const val SELECTION_CHOSEN = "selection_chosen"
    const val CHUNKING_INFERRED = "chunking_inferred"
    const val SOURCE_LANGUAGE_COMBINED = "source_language_combined"

    const val TEMPLATE_NOT_ENABLED = "template_not_enabled"
    const val LANGUAGE_UNKNOWN = "language_unknown"
    const val LOW_DISK_SPACE = "low_disk_space"
    const val NOTHING_USABLE = "nothing_usable"
    const val PROJECT_ON_ANOTHER_EDITION = "project_on_another_edition"
    const val PROJECT_HOLDS_OTHER_RECORDINGS = "project_holds_other_recordings"
    const val IMPORT_FAILED = "import_failed"
    const val NOT_VERIFIED = "not_verified"
    const val FAILED = "failed"
    const val FAILED_REPEATEDLY = "failed_repeatedly"

    fun of(problem: MigrationProblem): JournalReason = when (problem) {
        is MigrationProblem.OutOfScope -> JournalReason(OUT_OF_SCOPE, detail = problem.anthology)
        MigrationProblem.EnglishUdb -> JournalReason(ENGLISH_UDB)
        is MigrationProblem.BookNotInTemplate -> JournalReason(BOOK_NOT_IN_TEMPLATE, detail = problem.book)
        is MigrationProblem.ChunkingContradicted -> JournalReason(CHUNKING_CONTRADICTED, detail = problem.detail)
        is MigrationProblem.TakeFileMissing ->
            JournalReason(TAKE_FILE_MISSING, problem.chapter, "${problem.startVerse}", problem.take.file.name)
        is MigrationProblem.RangeUnknown ->
            JournalReason(RANGE_UNKNOWN, problem.chapter, "${problem.startVerse}", problem.take.file.name)
        is MigrationProblem.RangeDisagrees ->
            JournalReason(RANGE_DISAGREES, problem.chapter, "${problem.startVerse}", problem.detail)
        is MigrationProblem.RangeNotInTemplate ->
            JournalReason(RANGE_NOT_IN_TEMPLATE, problem.chapter, problem.verses.text, problem.template.name)
        is MigrationProblem.AudioUnreadable ->
            JournalReason(AUDIO_UNREADABLE, problem.chapter, problem.take.verses?.text, problem.reason)
        is MigrationProblem.AudioFormatUnexpected ->
            JournalReason(AUDIO_FORMAT_UNEXPECTED, problem.chapter, problem.take.verses?.text, problem.detail)
        is MigrationProblem.AudioRepaired -> JournalReason(AUDIO_REPAIRED, problem.chapter, problem.take.verses?.text)
        is MigrationProblem.InnerMarkersSetAside -> JournalReason(
            INNER_MARKERS_SET_ASIDE, problem.chapter, problem.take.verses?.text, problem.markers.joinToString { it.label }
        )
        is MigrationProblem.SelectionChosen -> JournalReason(SELECTION_CHOSEN, problem.chapter, "${problem.startVerse}")
        is MigrationProblem.ChunkingInferred -> JournalReason(CHUNKING_INFERRED, detail = problem.how)
        is MigrationProblem.SourceLanguageCombined ->
            JournalReason(SOURCE_LANGUAGE_COMBINED, detail = problem.sourceLanguages.sorted().joinToString())
    }

    fun of(refusal: RefusalReason): JournalReason = when (refusal) {
        is RefusalReason.OnAnotherEdition -> JournalReason(PROJECT_ON_ANOTHER_EDITION)
        is RefusalReason.HoldsOtherRecordings -> JournalReason(PROJECT_HOLDS_OTHER_RECORDINGS, detail = "${refusal.count}")
    }

    fun of(failure: VerificationFailure): JournalReason = when (failure) {
        VerificationFailure.ProjectMissing -> JournalReason(NOT_VERIFIED, detail = "project missing")
        is VerificationFailure.WrongEdition -> JournalReason(NOT_VERIFIED, detail = "wrong edition")
        is VerificationFailure.ChapterMissing -> JournalReason(NOT_VERIFIED, failure.chapter, detail = "chapter missing")
        is VerificationFailure.TakeMissing -> JournalReason(NOT_VERIFIED, failure.take.chapter, detail = "take missing")
        is VerificationFailure.TakeNotSelected -> JournalReason(NOT_VERIFIED, failure.take.chapter, detail = "take not selected")
    }

    private val IntRange.text: String get() = if (first == last) "$first" else "$first-$last"
}
