package org.bibletranslationtools.bttrecorder2.legacymigration

import java.io.File

/**
 * A project the old BTT-Recorder left on this device, as its database and files describe it.
 *
 * Facts only: nothing here decides what the migration does with it. Uncertainty is part of the
 * types (a missing mode is a null [chunking], a missing file is [LegacyTake.onDisk] false, an
 * unreadable filename is a null [LegacyTake.verses]) so the planner can't overlook it.
 */
data class LegacyProject(
    /** `projects._id`, for the log and the journal. */
    val id: Int,
    /** The old app's anthology: `ot`, `nt`, `obs`, … */
    val anthology: String,
    val book: LegacyBook,
    /** `languages.code` of the language recorded. */
    val targetLanguage: String,
    /** `languages.code` of the source the recordist listened to, when the project named one. */
    val sourceLanguage: String?,
    /** `versions.slug`: `ulb`, `udb` or `reg`, the old app's label for the recording. */
    val versionLabel: String,
    /** How the old app divided the project's chapters; null when its `modes` row is missing. */
    val chunking: LegacyChunking?,
    /** Free text, as typed into the old app. */
    val contributors: String?,
    /** `translations/<lang>/<version>/<book>` in the old app's storage. */
    val folder: File,
    val chapters: List<LegacyChapter>,
    /** Files in [folder] that no take row points at. Never migrated, never deleted. */
    val unreferencedFiles: List<File> = emptyList()
)

data class LegacyBook(val slug: String, val number: Int)

/**
 * How the old app divided a project's chapters for recording: its `modes.type`. `single` is one
 * verse at a time; `multi` is the chunks in its chunk definitions.
 */
enum class LegacyChunking { VERSE_BY_VERSE, CHUNKS }

data class LegacyChapter(val number: Int, val chunks: List<LegacyChunk>)

/**
 * A `units` row: one verse or chunk that has been recorded. The old app created it with the first
 * take and keyed it only by its first verse (it never writes `units.end_verse`), so a chunk's range
 * comes from its takes.
 */
data class LegacyChunk(
    val id: Int,
    val startVerse: Int,
    /** `units.chosen_take_fk` as stored. It can be null, or point at a take that's gone. */
    val selectedTakeId: Int?,
    val takes: List<LegacyTake>
)

data class LegacyTake(
    val id: Int,
    val number: Int,
    /** The recordist's rating. The old app selected the highest rated, then newest, take. */
    val rating: Int,
    /** When it was recorded, in epoch milliseconds, when known. */
    val recordedAt: Long?,
    val file: File,
    val onDisk: Boolean,
    /** The range in the filename (`…_v04-06_t01.wav`), or null if the name doesn't parse. */
    val verses: IntRange?,
    /** What the old app wrote into the WAV about the take, or null if it can't be read. */
    val metadata: LegacyTakeMetadata?
)

/** The JSON the old app wrote into each take's WAV. */
data class LegacyTakeMetadata(
    /** Its `mode`. */
    val chunking: LegacyChunking?,
    /** Its `startv`–`endv`. */
    val verses: IntRange?,
    /** The verses the recordist marked inside the take, by position. */
    val markers: List<LegacyMarker> = emptyList()
)

/** A verse the recordist marked inside a take: its label (a verse number) at frame [frame]. */
data class LegacyMarker(val label: String, val frame: Long) {
    val verse: Int? get() = label.trim().toIntOrNull()
}
