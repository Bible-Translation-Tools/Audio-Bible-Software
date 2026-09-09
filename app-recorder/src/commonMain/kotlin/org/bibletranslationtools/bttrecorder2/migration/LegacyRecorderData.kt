package org.bibletranslationtools.bttrecorder2.migration

/**
 * The legacy Android-only BTT-Recorder's project data, as read out of its own SQLite database.
 *
 * A flattened view of the legacy schema in its own terms, not Orature's, which keeps the reader a
 * report of what is on disk and leaves every interpretation to [MigrateLegacyRecorderProjects].
 */
data class LegacyProject(
    val id: Int,
    /** `languages.slug` of the recording's target language. */
    val targetLanguageSlug: String,
    /**
     * `languages.slug` of the source the recordist worked from. Nullable in the legacy schema;
     * callers fall back to [DEFAULT_SOURCE_LANGUAGE].
     */
    val sourceLanguageSlug: String?,
    /**
     * `versions.slug`: `ulb`, `udb` or `reg`. It labelled the translation being made rather than a
     * source text, so it selects no source (see [InitializeModeSources.IDENTIFIER]). It is still
     * what tells two legacy projects for one book and language apart, so it remains part of [key]
     * and of the legacy directory path.
     */
    val versionSlug: String,
    val bookSlug: String,
    val bookNumber: Int,
    val mode: LegacyMode,
    /** `projects.contributors`, a free-form string the legacy UI collected. */
    val contributors: String?,
    /** `projects.source_audio_path`, wherever the user picked the Archive of Holding from. */
    val sourceAudioPath: String?,
    val chapters: List<LegacyChapter>
) {
    /**
     * Stable identity for the ledger. It mirrors the legacy database's own uniqueness rule,
     * `UNIQUE(book_fk, target_language_fk, version_fk)`, so it survives re-reads and cannot collide
     * between two legacy projects.
     */
    val key: String get() = "$targetLanguageSlug/$versionSlug/$bookSlug"

    val takeCount: Int get() = chapters.sumOf { chapter -> chapter.units.sumOf { it.takes.size } }

    companion object {
        const val DEFAULT_SOURCE_LANGUAGE = "en"
    }
}

data class LegacyChapter(
    val number: Int,
    val units: List<LegacyUnit>
)

/**
 * One recordable unit. In verse mode [startVerse] equals [endVerse]; in chunk mode the unit spans a
 * verse range, which migrates onto a single bridged Orature row.
 */
data class LegacyUnit(
    val startVerse: Int,
    val endVerse: Int,
    /** `units.chosen_take_fk`, the take the recordist settled on, or null if none was chosen. */
    val chosenTakeId: Int?,
    val takes: List<LegacyTake>
)

data class LegacyTake(
    val id: Int,
    val number: Int,
    /** Bare filename as stored in `takes.filename`, resolved against the chapter directory. */
    val filename: String
)

/**
 * How the legacy project divided a chapter for recording.
 *
 * Read from `modes.type`, whose `single` / `multi` values are unambiguous, in preference to
 * `modes.slug`, which carries display wording.
 */
enum class LegacyMode {
    /** `single`: one unit per verse. */
    VERSE,

    /** `multi`: units span the verse ranges given by the legacy chunk definitions. */
    CHUNK;

    companion object {
        fun fromTypeOrSlug(value: String?): LegacyMode = when (value?.lowercase()) {
            "multi", "chunk" -> CHUNK
            else -> VERSE
        }
    }
}
