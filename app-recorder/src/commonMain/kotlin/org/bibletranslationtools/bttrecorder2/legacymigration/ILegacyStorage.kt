package org.bibletranslationtools.bttrecorder2.legacymigration

import java.io.File

/** Where the old BTT-Recorder kept its data on this device: what the archive saves and the delete removes. */
interface ILegacyStorage {
    /** The old app's database, or null when there is none. */
    val databaseFile: File?

    /** The old app's recordings folder (`translations/`), or null when it isn't available. */
    val translationsDir: File?

    /** Deletes the old app's database. True when it is gone. */
    fun deleteDatabase(): Boolean
}
