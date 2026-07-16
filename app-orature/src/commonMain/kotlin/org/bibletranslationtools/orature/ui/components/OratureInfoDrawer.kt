package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.aboutOrature
import org.bibletranslationtools.orature.resources.aboutOratureDescription
import org.bibletranslationtools.orature.resources.applicationLogs
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.currentVersion
import org.bibletranslationtools.orature.resources.description
import org.bibletranslationtools.orature.resources.errorReport
import org.bibletranslationtools.orature.resources.errorReportDescription
import org.bibletranslationtools.orature.resources.errorReportSent
import org.bibletranslationtools.orature.resources.information
import org.bibletranslationtools.orature.resources.na
import org.bibletranslationtools.orature.resources.sendErrorReport
import org.bibletranslationtools.orature.resources.viewLogs

/**
 * Orature's info drawer, rebuilt faithfully against the JVM `InfoView` (a scrollable 550dp
 * panel, same surface + 40dp padding + 30dp section gaps as the settings drawer). Sections in
 * InfoView order:
 *  - header (title + close ✕)
 *  - About Orature: subtitle + description text
 *  - Current Version: a FILLED highlighted box with the version string (or "Not Available")
 *  - Application Logs: subtitle + a "View Logs" button (stub — the JVM opens the logs dir via
 *    java.awt.Desktop; no logs-dir browser backend is ported yet, so this is visual)
 *  - Error Report: subtitle + description + a bound message textarea + a submit-timestamp
 *    status line + a "Send Error Report" button (disabled while empty)
 *
 * The JVM InfoView embeds an install4j `UpdaterView` between Version and Application Logs; the
 * auto-updater is desktop-install4j-specific and not ported, so it is omitted here. InfoView has
 * NO third-party licenses / attribution list and NO external links, so none are shown.
 */
@Composable
fun OratureInfoDrawer(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: org.bibletranslationtools.orature.ui.viewmodels.OratureInfoViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel { org.bibletranslationtools.orature.ui.viewmodels.OratureInfoViewModel() }
) {
    // Error-report message + submit timestamp. The JVM AppInfoViewModel throws an
    // ErrorReportException (caught by the crash reporter) and stamps the submit time; no such
    // reporter backend is ported, so submitting just records the timestamp locally.
    var errorDescription by remember { mutableStateOf("") }
    var reportTimeStamp by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier.width(550.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.information),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.close),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // ── About Orature ───────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.aboutOrature),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(Res.string.aboutOratureDescription),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // ── Current Version (filled highlight box) ──────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(Res.string.currentVersion),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = viewModel.version.ifBlank { stringResource(Res.string.na) },
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Application Logs ────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(Res.string.applicationLogs),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedButton(onClick = viewModel::browseLogs, enabled = viewModel.canViewLogs) {
                    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.viewLogs))
                }
            }

            // ── Error Report ────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(Res.string.errorReport),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(Res.string.errorReportDescription),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(Res.string.description),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = errorDescription,
                    onValueChange = { errorDescription = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )

                reportTimeStamp?.let { stamp ->
                    Text(
                        text = stringResource(Res.string.errorReportSent, stamp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (errorDescription.isNotBlank()) {
                            reportTimeStamp = currentTimestamp
                            errorDescription = ""
                        }
                    },
                    enabled = errorDescription.isNotBlank()
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.sendErrorReport))
                }
            }
        }
    }
}

/** A submit timestamp for the error-report status line (mirrors AppInfoViewModel.reportTimeStamp). */
@OptIn(ExperimentalTime::class)
private val currentTimestamp: String
    get() = Clock.System.now().toString()
