package org.bibletranslationtools.bttrecorder2.ui

/** Stable Compose test tags for e2e / UI tests. Prefer contentDescription when present. */
object TestTags {
    const val SETTINGS_SCREEN = "recorder-settings-screen"
    const val PROJECT_MANAGEMENT = "recorder-project-management"
    const val WIZARD_SCREEN = "recorder-wizard-screen"
    const val RECORDER_SCREEN = "recorder-screen"
    const val PLAYBACK_SCREEN = "playback-screen"
    const val RECORD_TRANSPORT = "recorder-record-transport"
    const val RECORD_STOP = "recorder-stop"

    fun projectCard(bookSlug: String) = "project-card-$bookSlug"
    fun projectRecord(bookSlug: String) = "project-record-$bookSlug"
    fun wizardRow(slug: String) = "wizard-row-$slug"
}
