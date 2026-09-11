package org.bibletranslationtools.bttrecorder2.migration

import java.io.File

/**
 * Read access to the legacy Android recorder's data, plus the one write it permits: deleting a
 * migrated project's take audio.
 *
 * An interface with per-platform bindings rather than `expect`/`actual`, because the Android
 * implementation needs the `Context` its Koin module already provides and the desktop one is a
 * no-op, the legacy app having been Android-only.
 *
 * The legacy database is never written. Once a project's audio is deleted it is the only remaining
 * record of what was there, so it is kept as an audit trail; idempotency comes from
 * [MigrationLedger] instead.
 */
interface LegacyRecorderStore {

    /**
     * True when there is legacy data worth reading. Existence checks only, no parsing, so migration
     * can do nothing on a fresh install without opening a database.
     */
    fun hasLegacyData(): Boolean

    /**
     * Every legacy project, with its chapters, units and takes. A missing or unreadable database
     * yields an empty list rather than throwing, so it cannot become a crash on first launch.
     */
    fun readProjects(): List<LegacyProject>

    /**
     * The on-disk take file, or null when it is missing. Legacy takes live on external app storage,
     * under `<externalFilesDir>/translations/<lang>/<version>/<book>/<cc>/`, which the user can
     * clear, so absence is a normal outcome.
     */
    fun takeFile(project: LegacyProject, chapterNumber: Int, take: LegacyTake): File?

    /**
     * The legacy source-audio container for a project, or null.
     *
     * `projects.source_audio_path` is tried first and then the basename under
     * `<externalFilesDir>/source_audio/`, because the recorded path can point somewhere scoped
     * storage no longer allows while the legacy app had already copied the file into its own
     * directory.
     */
    fun sourceAudioFile(project: LegacyProject): File?

    /** Total bytes of the project's take audio, for the pre-flight free-space check. */
    fun audioSizeBytes(project: LegacyProject): Long

    /**
     * Deletes the project's take directory, pruning the version and language directories above it
     * when they are left empty, as the legacy app's own deletion did.
     *
     * Called only once the project is fully migrated and verified.
     *
     * @return false when the filesystem refused, which is reported rather than treated as an error:
     *   leftover files cost space but break nothing.
     */
    fun deleteProjectAudio(project: LegacyProject): Boolean
}

/** For desktop and any other non-Android host, where the legacy recorder never ran. */
class NoLegacyRecorderStore : LegacyRecorderStore {
    override fun hasLegacyData() = false
    override fun readProjects(): List<LegacyProject> = emptyList()
    override fun takeFile(project: LegacyProject, chapterNumber: Int, take: LegacyTake): File? = null
    override fun sourceAudioFile(project: LegacyProject): File? = null
    override fun audioSizeBytes(project: LegacyProject): Long = 0
    override fun deleteProjectAudio(project: LegacyProject) = true
}
