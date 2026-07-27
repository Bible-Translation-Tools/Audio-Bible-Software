package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.audioNotAvailable
import org.bibletranslationtools.orature.resources.licenseStatement
import org.bibletranslationtools.orature.resources.sourceAudio
import org.bibletranslationtools.orature.resources.zoomIn
import org.bibletranslationtools.orature.resources.zoomOut
import org.bibletranslationtools.orature.ui.OratureColors
import org.jetbrains.compose.resources.stringResource

/**
 * Shown full-bleed while an external editor/recorder plugin has a take (or, for Narration, a
 * chapter/verse file) open (JVM: `PluginOpenedPage` wrapping `SourceContent`) — a zoom-controlled
 * source-text reading view (the file being edited isn't shown; only the SOURCE side, which is
 * always safe to keep playing/reading) with a bottom media bar that plays the source audio, or
 * reports it's unavailable. Shared by every screen that can launch a plugin (Final Review, Blind
 * Draft, Peer Edit, Narration) so the same reading/waiting experience shows everywhere, matching
 * JVM's single reusable `SourceContent` control.
 */
@Composable
fun OraturePluginOpenedCover(
    contentTitle: String,
    sourceText: String,
    sourceLicense: String,
    isSourcePlaying: Boolean,
    sourcePositionMs: Int,
    sourceDurationMs: Int,
    sourceRate: Double,
    onToggleSource: () -> Unit,
    onSeekSource: (Float) -> Unit,
    onSetSourceRate: (Double) -> Unit
) {
    // JVM: `SourceContent.zoomRateProperty` (default 100, range 50-200, step 10) scales the verse
    // text size via `.text-zoom-N` CSS classes; not persisted, so plain remembered state suffices.
    var zoomRate by remember { mutableStateOf(100) }
    val verseFontSize = (15 * zoomRate / 100).sp

    Column(modifier = Modifier.fillMaxSize()) {
        // Header: zoom control (left) + centered title (JVM: `.source-content__zoom-control` +
        // `.source-content__title`, no minimize button in this usage).
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { zoomRate = (zoomRate - 10).coerceAtLeast(50) }) {
                    Icon(Icons.Filled.ZoomOut, contentDescription = stringResource(Res.string.zoomOut), tint = OratureColors.RegularText)
                }
                Text("$zoomRate%", fontSize = 15.sp, color = OratureColors.RegularText)
                IconButton(onClick = { zoomRate = (zoomRate + 10).coerceAtMost(200) }) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(Res.string.zoomIn), tint = OratureColors.RegularText)
                }
            }
            Text(
                contentTitle,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = OratureColors.RegularText,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Body: source text, one line per verse (JVM: `SourceContent.buildChunkText` — a plain
        // "N. text" label per line, no separate verse-number styling), then the license footer.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sourceText.split("\n").filter { it.isNotEmpty() }.forEach { line ->
                Text(line, fontSize = verseFontSize, color = OratureColors.RegularText)
            }
            if (sourceLicense.isNotEmpty()) {
                Text(
                    stringResource(Res.string.licenseStatement, sourceLicense),
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    color = OratureColors.NoteText,
                    modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
                )
            }
        }

        // Bottom media bar (JVM: `.source-content__bottom`/`.source-content__control-group`).
        Box(
            modifier = Modifier.fillMaxWidth().background(OratureColors.SurfaceSecondary).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (sourceDurationMs > 0) {
                OratureAudioPlayerRow(
                    isPlaying = isSourcePlaying,
                    isActive = true,
                    positionMs = sourcePositionMs,
                    durationMs = sourceDurationMs,
                    onToggle = onToggleSource,
                    onSeek = onSeekSource,
                    sideLabel = stringResource(Res.string.sourceAudio)
                ) {
                    PlaybackSpeedMenu(rate = sourceRate, onRateSelected = onSetSourceRate)
                }
            } else {
                Text(stringResource(Res.string.audioNotAvailable), fontSize = 15.sp, color = OratureColors.NoteText)
            }
        }
    }
}
