package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.legacymigration.JournalEntry
import org.bibletranslationtools.bttrecorder2.legacymigration.JournalReasons
import org.bibletranslationtools.bttrecorder2.legacymigration.JournalState
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationSummary
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_ok
import org.bibletranslationtools.shared.resources.legacy_migration_reason_chunks
import org.bibletranslationtools.shared.resources.legacy_migration_reason_english_udb
import org.bibletranslationtools.shared.resources.legacy_migration_reason_existing
import org.bibletranslationtools.shared.resources.legacy_migration_reason_failed
import org.bibletranslationtools.shared.resources.legacy_migration_reason_language
import org.bibletranslationtools.shared.resources.legacy_migration_reason_out_of_scope
import org.bibletranslationtools.shared.resources.legacy_migration_reason_recordings
import org.bibletranslationtools.shared.resources.legacy_migration_reason_space
import org.bibletranslationtools.shared.resources.legacy_migration_summary_entry
import org.bibletranslationtools.shared.resources.legacy_migration_summary_kept
import org.bibletranslationtools.shared.resources.legacy_migration_summary_moved
import org.bibletranslationtools.shared.resources.legacy_migration_summary_title
import org.bibletranslationtools.shared.resources.legacy_migration_summary_waiting
import org.bibletranslationtools.shared.resources.list_item_bullet
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Tells the user, once, what moving their old BTT-Recorder projects did: how much moved, what
 * stayed in the old app's storage and why, and what waits for later. The details are in the
 * migration journal, among the app's logs.
 */
@Composable
fun LegacyMigrationSummaryDialog(summary: MigrationSummary, onDismiss: () -> Unit) {
    // Moved, but with something left behind: the recordings that did move count as moved, and the
    // project is listed with what stayed.
    val kept = summary.kept + summary.moved.filter { it.state == JournalState.KEPT }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.legacy_migration_summary_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (summary.moved.isNotEmpty()) {
                    Text(stringResource(Res.string.legacy_migration_summary_moved, summary.chaptersMoved, summary.booksMoved))
                }
                EntryList(Res.string.legacy_migration_summary_kept, kept)
                EntryList(Res.string.legacy_migration_summary_waiting, summary.waiting)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_ok)) }
        }
    )
}

@Composable
private fun EntryList(heading: StringResource, entries: List<JournalEntry>) {
    if (entries.isEmpty()) return
    Text(
        stringResource(heading),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
    entries.sortedBy { it.location }.forEach { entry ->
        val line = stringResource(Res.string.legacy_migration_summary_entry, entry.location, stringResource(reasonFor(entry)))
        Text(stringResource(Res.string.list_item_bullet, line))
    }
}

/** The one reason worth telling the user about [entry], by its journal reason codes. */
private fun reasonFor(entry: JournalEntry): StringResource {
    val codes = entry.reasons.map { it.code }.toSet()
    return when {
        JournalReasons.TEMPLATE_NOT_ENABLED in codes -> Res.string.legacy_migration_reason_chunks
        JournalReasons.LOW_DISK_SPACE in codes -> Res.string.legacy_migration_reason_space
        JournalReasons.LANGUAGE_UNKNOWN in codes -> Res.string.legacy_migration_reason_language
        JournalReasons.PROJECT_ON_ANOTHER_EDITION in codes || JournalReasons.PROJECT_HOLDS_OTHER_RECORDINGS in codes ->
            Res.string.legacy_migration_reason_existing
        JournalReasons.OUT_OF_SCOPE in codes -> Res.string.legacy_migration_reason_out_of_scope
        JournalReasons.ENGLISH_UDB in codes -> Res.string.legacy_migration_reason_english_udb
        codes.any { it in RECORDING_CODES } -> Res.string.legacy_migration_reason_recordings
        else -> Res.string.legacy_migration_reason_failed
    }
}

/** Codes that mean some recordings couldn't be read, or had no place in the project. */
private val RECORDING_CODES = setOf(
    JournalReasons.TAKE_FILE_MISSING,
    JournalReasons.RANGE_UNKNOWN,
    JournalReasons.RANGE_DISAGREES,
    JournalReasons.RANGE_NOT_IN_TEMPLATE,
    JournalReasons.AUDIO_UNREADABLE,
    JournalReasons.AUDIO_FORMAT_UNEXPECTED,
    JournalReasons.BOOK_NOT_IN_TEMPLATE,
    JournalReasons.CHUNKING_CONTRADICTED,
    JournalReasons.NOTHING_USABLE
)
