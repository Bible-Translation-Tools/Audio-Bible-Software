package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Output
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.path
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.available_takes
import org.bibletranslationtools.orature.resources.best_take
import org.bibletranslationtools.orature.resources.cancel
import org.bibletranslationtools.orature.resources.chunk
import org.bibletranslationtools.orature.resources.delete
import org.bibletranslationtools.orature.resources.edit
import org.bibletranslationtools.orature.resources.export
import org.bibletranslationtools.orature.resources.new_recording
import org.bibletranslationtools.orature.resources.pause
import org.bibletranslationtools.orature.resources.playSource
import org.bibletranslationtools.orature.resources.resume
import org.bibletranslationtools.orature.resources.save
import org.bibletranslationtools.orature.resources.select
import org.bibletranslationtools.orature.resources.sourceAudio
import org.bibletranslationtools.orature.resources.take
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.components.OratureAudioPlayerRow
import org.bibletranslationtools.orature.ui.components.OraturePluginOpenedCover
import org.bibletranslationtools.orature.ui.components.PlaybackSpeedMenu
import org.bibletranslationtools.orature.ui.viewmodels.OratureBlindDraftViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureTakeCard
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * The Blind Draft step body (JVM: `BlindDraft`): plays the active chunk's source audio (with a
 * playback-speed control), lists its target takes (best + available, each its own scrub player),
 * and offers a new recording. Select a take (via its star) to make it "best".
 */
@Composable
fun OratureBlindDraftScreen(viewModel: OratureBlindDraftViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // JVM: `.translation-view { -fx-background-color: -wa-foreground; }` — the translation page's
    // root is white, not the app's light-gray page background.
    Box(modifier = Modifier.fillMaxSize().background(OratureColors.Foreground), contentAlignment = Alignment.Center) {
        when {
            uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
            !uiState.hasChunk -> Text(stringResource(Res.string.chunk), color = OratureColors.RegularText)
            uiState.error != null -> Text(uiState.error!!, color = OratureColors.RegularText)
            uiState.isPluginOpen -> OraturePluginOpenedCover(
                contentTitle = uiState.activeContentTitle,
                sourceText = uiState.sourceText,
                sourceLicense = uiState.sourceLicense,
                isSourcePlaying = uiState.isSourcePlaying,
                sourcePositionMs = uiState.sourcePositionMs,
                sourceDurationMs = uiState.sourceDurationMs,
                sourceRate = uiState.sourceRate,
                onToggleSource = viewModel::toggleSource,
                onSeekSource = viewModel::seekSource,
                onSetSourceRate = viewModel::setSourceRate
            )
            uiState.recording -> RecordingSection(
                waveformProvider = viewModel::currentRecordingWaveform,
                isActive = uiState.recordingActive,
                onToggle = viewModel::toggleRecording,
                onSave = viewModel::saveRecording,
                onCancel = viewModel::cancelRecording
            )
            // JVM: `.blind-draft-section { border-width: 0 0 1 0; border-color: -wa-border-light; }`
            // — the page is three sections (source audio / Best Take / Available Takes), each with
            // its own bottom rule, followed by the (unbordered) New Recording button row.
            else -> Column(modifier = Modifier.fillMaxSize()) {
                // Section 1: source audio for the chunk (JVM: `simpleaudioplayer` with
                // enablePlaybackRateProperty). `.blind-draft-section { padding: 16; spacing: 24; }`
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        "${stringResource(Res.string.chunk)} ${uiState.chunkTitle}",
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = OratureColors.RegularText
                    )
                    OratureAudioPlayerRow(
                        isPlaying = uiState.isSourcePlaying,
                        // The source player is always the "current" one once loaded — decoupled
                        // from isPlaying so pausing doesn't disable the slider or reset position.
                        isActive = uiState.sourceDurationMs > 0,
                        positionMs = uiState.sourcePositionMs,
                        durationMs = uiState.sourceDurationMs,
                        onToggle = viewModel::toggleSource,
                        onSeek = viewModel::seekSource,
                        sideLabel = stringResource(Res.string.sourceAudio)
                    ) {
                        PlaybackSpeedMenu(rate = uiState.sourceRate, onRateSelected = viewModel::setSourceRate)
                    }
                }
                HorizontalDivider(color = OratureColors.BorderLight)

                // Section 2: Best Take. `.blind-draft-section--top-indent { padding: 32 16 16 16; }`
                // — same section spacing (24), but 32dp on top instead of 16 (extra room below
                // the divider).
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(stringResource(Res.string.best_take), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = OratureColors.NoteText)
                    uiState.selectedTake?.let { t ->
                        TakeRow(
                            t,
                            isCurrent = uiState.currentTakeId == t.id,
                            isPlaying = uiState.currentTakeId == t.id && uiState.isTakePlaying,
                            positionMs = uiState.takePositionMs,
                            onPlay = { viewModel.toggleTake(t.id) },
                            onSeek = { f -> viewModel.seekTake(t.id, f) },
                            onSelect = null,
                            onDelete = { viewModel.deleteTake(t.id) },
                            onExport = { dir -> viewModel.exportTake(t.id, dir) },
                            onEdit = if (uiState.canEditExternally) ({ viewModel.editTakeExternally(t.id) }) else null
                        )
                    }
                }
                HorizontalDivider(color = OratureColors.BorderLight)

                // Section 3: Available Takes. `.blind-draft-section--top-indent`, vgrow=ALWAYS.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .padding(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(stringResource(Res.string.available_takes), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = OratureColors.NoteText)
                    // JVM: `.take-list { -fx-spacing: 32; }` — the gap between take rows.
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(32.dp)
                    ) {
                        for (t in uiState.availableTakes) {
                            TakeRow(
                                t,
                                isCurrent = uiState.currentTakeId == t.id,
                                isPlaying = uiState.currentTakeId == t.id && uiState.isTakePlaying,
                                positionMs = uiState.takePositionMs,
                                onPlay = { viewModel.toggleTake(t.id) },
                                onSeek = { f -> viewModel.seekTake(t.id, f) },
                                onSelect = { viewModel.selectTake(t.id) },
                                onDelete = { viewModel.deleteTake(t.id) },
                                onExport = { dir -> viewModel.exportTake(t.id, dir) },
                                onEdit = if (uiState.canEditExternally) ({ viewModel.editTakeExternally(t.id) }) else null
                            )
                        }
                    }
                }
                HorizontalDivider(color = OratureColors.BorderLight)

                // New Recording button row (JVM: `consume__bottom` — not a `.blind-draft-section`,
                // no border).
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    // JVM: `.btn--primary` — a rounded RECTANGLE (12dp corners), not a full pill,
                    // with a 1dp dark-navy border.
                    Button(
                        onClick = viewModel::onRecordNew,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OratureColors.PrimaryDarkest),
                        colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(Res.string.new_recording), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/**
 * The active-recording section (JVM: `RecordingSection`): a live mic waveform with pause/resume,
 * save, and cancel. The waveform is the `ActiveRecordingRenderer` min/max buffer, drawn each frame.
 */
@Composable
internal fun RecordingSection(
    waveformProvider: () -> FloatArray,
    isActive: Boolean,
    onToggle: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    var frameTick by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameNanos { frameTick = it }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFFE5E8EB))
        ) {
            frameTick // redraw each frame
            val midY = size.height / 2f
            val scale = size.height / 2f / 32768f
            val buffer = waveformProvider()
            val columns = buffer.size / 2
            for (col in 0 until columns) {
                val x = col.toFloat() / columns * size.width
                drawLine(
                    androidx.compose.ui.graphics.Color(0xFFD32F2F),
                    androidx.compose.ui.geometry.Offset(x, midY - buffer[col * 2] * scale),
                    androidx.compose.ui.geometry.Offset(x, midY - buffer[col * 2 + 1] * scale),
                    strokeWidth = 1f
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(OratureColors.Foreground).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onToggle,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
            ) {
                Icon(if (isActive) Icons.Filled.Pause else Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    if (isActive) stringResource(Res.string.pause) else stringResource(Res.string.resume),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            if (!isActive) {
                OutlinedButton(onClick = onSave, shape = RoundedCornerShape(12.dp)) { Text(stringResource(Res.string.save)) }
            }
            Spacer(Modifier.weight(1f))
            if (!isActive) {
                OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(12.dp)) { Text(stringResource(Res.string.cancel)) }
            }
        }
    }
}

/** mm:ss remaining-time text (JVM: `remainingTimecode`) — duration minus elapsed while active. */
private fun remainingTimeText(positionMs: Int, durationMs: Int): String {
    val remaining = (durationMs - positionMs).coerceAtLeast(0)
    val totalSeconds = remaining / 1000
    return "${(totalSeconds / 60).toString().padStart(2, '0')}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/**
 * JVM `.btn--tertiary`: a rounded SQUARE icon button (12dp corners, not circular) with a 2dp
 * border. When [active] (JVM: `:active` pseudo-class — used for the selected take's star), it
 * fills solid dark-navy instead of showing a border, matching the "this take is best" affordance.
 */
@Composable
private fun TertiaryIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    active: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (active) {
                    Modifier.background(OratureColors.PrimaryDarkest, RoundedCornerShape(12.dp))
                } else {
                    Modifier.border(2.dp, OratureColors.SurfaceTertiary, RoundedCornerShape(12.dp))
                }
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * A take row (JVM: `ChunkTakeCard`): a `SimpleAudioPlayer` (play/pause + scrub slider + remaining
 * time) followed by Export, Delete, and a Select star (filled + inert when this take is already the
 * best take; outline and clickable to promote otherwise).
 */
@Composable
private fun TakeRow(
    take: OratureTakeCard,
    isCurrent: Boolean,
    isPlaying: Boolean,
    positionMs: Int,
    onPlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelect: (() -> Unit)?,
    onDelete: () -> Unit,
    onExport: (File) -> Unit,
    onEdit: (() -> Unit)? = null
) {
    // A minimal export: copy the take's audio file into the chosen folder (JVM offers an mp3
    // re-encode + filename prompt; this port keeps the source format and skips the rename step).
    val dirPicker = rememberDirectoryPickerLauncher { dir ->
        dir?.let { onExport(File(it.path)) }
    }

    // JVM's `ChunkTakeCard`/`take-card` has no border or card background around the whole row —
    // just the inline player + three tertiary buttons on plain page background.
    // `.take-card { -fx-spacing: 12; }`
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OratureAudioPlayerRow(
            isPlaying = isPlaying,
            // Interactive + position-tracking whenever this row owns the shared take player —
            // whether playing OR paused — so pausing doesn't disable the slider or snap it to 0.
            isActive = isCurrent,
            positionMs = positionMs,
            durationMs = take.durationMs,
            onToggle = onPlay,
            onSeek = onSeek,
            sideLabel = remainingTimeText(if (isCurrent) positionMs else 0, take.durationMs),
            title = "${stringResource(Res.string.take)} ${take.number}",
            modifier = Modifier.weight(1f)
        )
        onEdit?.let {
            TertiaryIconButton(onClick = it, contentDescription = stringResource(Res.string.edit)) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = OratureColors.RegularText80)
            }
        }
        TertiaryIconButton(onClick = { dirPicker.launch() }, contentDescription = stringResource(Res.string.export)) {
            Icon(Icons.Filled.Output, contentDescription = null, tint = OratureColors.RegularText80)
        }
        TertiaryIconButton(onClick = onDelete, contentDescription = stringResource(Res.string.delete)) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = OratureColors.RegularText80)
        }
        // Select star (JVM: `.btn--tertiary:active` when this is the best take — a filled dark-navy
        // pill with a light star icon; outline star on plain tertiary otherwise. Clicking an
        // outline star promotes that take to best).
        TertiaryIconButton(
            onClick = { onSelect?.invoke() },
            enabled = onSelect != null,
            active = take.selected,
            contentDescription = stringResource(Res.string.select)
        ) {
            Icon(
                if (take.selected) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = if (take.selected) OratureColors.Foreground else OratureColors.RegularText80
            )
        }
    }
}
