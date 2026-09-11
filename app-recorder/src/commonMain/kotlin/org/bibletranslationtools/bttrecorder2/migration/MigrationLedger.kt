package org.bibletranslationtools.bttrecorder2.migration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Per-project record of what migration did, kept next to the app's own data.
 *
 * Migration surfaces nothing in the UI, so this file and the log are the only places its outcome
 * exists. It records enough to reconstruct a run: takes copied, takes skipped and why, whether the
 * legacy audio was deleted, and the original name of any source audio carried across.
 *
 * It is also how a run resumes. The `Installable` marker says only that migration finished as a
 * whole, whereas this says which projects individually reached a terminal state, so an interrupted
 * run does not redo completed ones. Writing an entry before deleting a project's legacy audio is
 * what keeps a crash between the two to a retried deletion rather than a lost project.
 */
class MigrationLedger(private val file: File) {

    private val logger = LoggerFactory.getLogger(MigrationLedger::class.java)

    @Serializable
    enum class State {
        /** Project and takes are in the database and verified, legacy audio not yet deleted. */
        MIGRATED,

        /** Verified, with the legacy take audio removed. Terminal. */
        CLEANED,

        /**
         * Not migrated, and cannot be by this build, for instance when no source resolves for the
         * project. Terminal, with [Entry.note] giving the reason.
         */
        SKIPPED,

        /**
         * Attempted and failed in a way that may succeed later, such as insufficient free space or
         * an unreadable file. Retried on the next launch.
         */
        FAILED
    }

    @Serializable
    data class Entry(
        val key: String,
        val state: State,
        val takesCopied: Int = 0,
        val takesSkipped: List<String> = emptyList(),
        /** Original filename of the legacy source-audio container copied in, if any. */
        val sourceAudio: String? = null,
        val note: String? = null
    ) {
        val isTerminal: Boolean get() = state == State.CLEANED || state == State.SKIPPED
    }

    @Serializable
    private data class Snapshot(val entries: List<Entry> = emptyList())

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private var entries: MutableMap<String, Entry> = load()

    private fun load(): MutableMap<String, Entry> {
        if (!file.isFile) return mutableMapOf()
        return try {
            json.decodeFromString(Snapshot.serializer(), file.readText())
                .entries.associateBy { it.key }.toMutableMap()
        } catch (e: Exception) {
            // A corrupt ledger must not block migration. At worst already-migrated projects are
            // reconsidered, which the per-take audio comparison makes harmless.
            logger.error("Unreadable migration ledger at ${file.path}; starting a fresh one", e)
            mutableMapOf()
        }
    }

    operator fun get(key: String): Entry? = entries[key]

    /** True when this project has reached a state that must not be reprocessed. */
    fun isDone(key: String): Boolean = entries[key]?.isTerminal == true

    /** Entries left in a non-terminal state, i.e. work still outstanding. */
    fun unfinished(): List<Entry> = entries.values.filterNot { it.isTerminal }

    /**
     * Writes [entry] through to disk immediately, never batched: callers rely on the write having
     * landed before they take an irreversible step such as deleting legacy audio.
     */
    fun put(entry: Entry) {
        entries[entry.key] = entry
        flush()
    }

    private fun flush() {
        try {
            file.parentFile?.mkdirs()
            // Write and rename, so an interrupted write cannot leave a truncated ledger that
            // would read as "nothing was migrated" on the next launch.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Snapshot.serializer(), Snapshot(entries.values.sortedBy { it.key })))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            logger.error("Could not write the migration ledger to ${file.path}", e)
        }
    }
}
