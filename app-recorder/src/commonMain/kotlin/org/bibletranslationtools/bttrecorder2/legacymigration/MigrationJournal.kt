package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Where each legacy project stands, kept in the app's data so a crash or a later launch never
 * repeats finished work, never loops, and never deletes what wasn't verified (M9).
 *
 * Every change is written to a temporary file that then replaces the journal, so the journal on
 * disk is always a whole one. A write that fails throws [JournalWriteFailed]; the migration stops
 * there, and nothing is deleted on the strength of an unrecorded state (fail closed).
 */
class MigrationJournal(private val file: File) {

    private val logger = LoggerFactory.getLogger(MigrationJournal::class.java)

    class JournalWriteFailed(cause: Throwable) : IOException("the migration journal couldn't be written", cause)

    /**
     * The journal as last written. A missing one is empty. One that can't be read is set aside,
     * beside it, and an empty one is used: nothing then counts as verified until it is verified
     * again, so this can only cause work to be redone, never a delete.
     */
    fun load(): JournalData {
        if (!file.isFile) return JournalData()
        return try {
            JSON.decodeFromString(JournalData.serializer(), file.readText())
                .takeIf { it.formatVersion <= JournalData.FORMAT_VERSION }
                ?: error("format version is newer than ${JournalData.FORMAT_VERSION}")
        } catch (e: Exception) {
            val aside = file.resolveSibling("${file.name}.unreadable-${System.currentTimeMillis()}")
            logger.error("The migration journal couldn't be read; it is kept as $aside and a new one started", e)
            file.renameTo(aside)
            JournalData()
        }
    }

    /** Writes [data] in place of the journal, whole. */
    fun write(data: JournalData) {
        try {
            file.parentFile?.mkdirs()
            val temporary = file.resolveSibling("${file.name}.writing")
            temporary.writeText(JSON.encodeToString(JournalData.serializer(), data))
            // rename(2) replaces the journal in one step on Android's file systems.
            if (!temporary.renameTo(file)) throw IOException("couldn't replace ${file.name}")
        } catch (e: Exception) {
            throw JournalWriteFailed(e)
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    }
}

@Serializable
data class JournalData(
    val formatVersion: Int = FORMAT_VERSION,
    /** By [LegacyProject.journalKey]. */
    val projects: Map<String, JournalEntry> = emptyMap()
) {
    fun entryFor(project: LegacyProject): JournalEntry? = projects[project.journalKey]

    fun with(entry: JournalEntry): JournalData = copy(projects = projects + (entry.key to entry))

    /** The legacy projects whose files may now be deleted: verified, or with nothing recorded. */
    val deletable: List<JournalEntry> get() = projects.values.filter { it.state.deletable }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

/**
 * Where a legacy project stands. [attempts] counts the runs that started on it without finishing
 * it, so a project that keeps failing (or crashing the app) is kept rather than tried forever.
 */
@Serializable
data class JournalEntry(
    val key: String,
    val legacyProjectId: Int,
    /** `<lang>/<version>/<book>`, as the old app filed it. */
    val location: String,
    val state: JournalState,
    val attempts: Int = 0,
    /** Chapters it contributed to verified chapter takes. */
    val chaptersMoved: Int = 0,
    /** Its recordings are in the new app, even if it was kept for something left out. */
    val imported: Boolean = false,
    val reasons: List<JournalReason> = emptyList(),
    /** Whether the summary has told the user about [state]. */
    val reported: Boolean = false,
    val updatedAt: Long = 0
)

@Serializable
enum class JournalState(val terminal: Boolean, val deletable: Boolean) {
    /** Being migrated. A run that finds it here was interrupted, and does it again. */
    PLANNED(terminal = false, deletable = false),

    /** Failed for something that can change, such as low disk space. Tried again on a later launch. */
    RETRY(terminal = false, deletable = false),

    /** It belongs on a template this version doesn't migrate onto. Planned again by each version. */
    DEFERRED(terminal = false, deletable = false),

    /** Every chapter take is in the new app, verified, and nothing that blocks deletion was left out. */
    VERIFIED(terminal = true, deletable = true),

    /** It held no recordings, so there was nothing to carry over. */
    NOTHING_RECORDED(terminal = true, deletable = true),

    /** Kept in the old app's storage for [JournalEntry.reasons]. Never tried again. */
    KEPT(terminal = true, deletable = false),

    /** Its files were deleted (M10). */
    LEGACY_DELETED(terminal = true, deletable = false)
}

/**
 * Why a project is kept or waiting, by a stable [code] (the summary words it), with where it
 * applies when it applies to part of a project.
 */
@Serializable
data class JournalReason(
    val code: String,
    val chapter: Int? = null,
    val verses: String? = null,
    val detail: String? = null
)

/** Identifies a legacy project in the journal: its row and where its files are. */
val LegacyProject.journalKey: String get() = "$id:$location"

val LegacyProject.location: String get() = "$targetLanguage/$versionLabel/${book.slug}"
