package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.sink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bibletranslationtools.bttrecorder2.legacymigration.ILegacyArchive
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.legacy_archive_body
import org.bibletranslationtools.shared.resources.legacy_archive_failed
import org.bibletranslationtools.shared.resources.legacy_archive_file_name
import org.bibletranslationtools.shared.resources.legacy_archive_removing
import org.bibletranslationtools.shared.resources.legacy_archive_save
import org.bibletranslationtools.shared.resources.legacy_archive_saving
import org.bibletranslationtools.shared.resources.legacy_archive_skip
import org.bibletranslationtools.shared.resources.legacy_archive_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.slf4j.LoggerFactory

private enum class ArchiveStep { OFFER, SAVING, REMOVING, FAILED }

/**
 * Offers to save an archive of the old BTT-Recorder's recordings before they are deleted (M10).
 * Saving streams it into a file the user picks (on Android, through the system's document
 * picker); declining deletes without one. Closing it without choosing deletes nothing, and the
 * offer comes back on the next launch.
 */
@Composable
fun LegacyArchiveDialog(archive: ILegacyArchive, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(ArchiveStep.OFFER) }
    val busy = step == ArchiveStep.SAVING || step == ArchiveStep.REMOVING

    fun choose(saved: Boolean) = scope.launch {
        step = ArchiveStep.REMOVING
        runCatching { withContext(Dispatchers.IO) { archive.choose(saved) } }
            .onFailure { logger.error("Deleting the old recordings failed; tried again next launch", it) }
        onDone()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(stringResource(Res.string.legacy_archive_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        when (step) {
                            ArchiveStep.OFFER -> Res.string.legacy_archive_body
                            ArchiveStep.SAVING -> Res.string.legacy_archive_saving
                            ArchiveStep.REMOVING -> Res.string.legacy_archive_removing
                            ArchiveStep.FAILED -> Res.string.legacy_archive_failed
                        }
                    ),
                    color = if (step == ArchiveStep.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                if (busy) LinearProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    val destination = FileKit.openFileSaver(
                        suggestedName = getString(Res.string.legacy_archive_file_name),
                        extension = "zip"
                    ) ?: return@launch
                    step = ArchiveStep.SAVING
                    val saved = try {
                        withContext(Dispatchers.IO) { archive.save(destination.sink()) }
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Saving the archive of the old recordings failed", e)
                        false
                    }
                    if (!saved) {
                        step = ArchiveStep.FAILED
                        return@launch
                    }
                    choose(saved = true)
                }
            }) { Text(stringResource(Res.string.legacy_archive_save)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { choose(saved = false) }) {
                Text(stringResource(Res.string.legacy_archive_skip))
            }
        }
    )
}

private val logger = LoggerFactory.getLogger("LegacyArchiveDialog")
