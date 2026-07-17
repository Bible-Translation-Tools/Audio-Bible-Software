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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.addBookMarker
import org.bibletranslationtools.orature.resources.addChapterMarker
import org.bibletranslationtools.orature.resources.addVerseMarker
import org.bibletranslationtools.orature.resources.chapter
import org.bibletranslationtools.orature.resources.chapterTitle
import org.bibletranslationtools.orature.resources.nextChapter
import org.bibletranslationtools.orature.resources.nextChunk
import org.bibletranslationtools.orature.resources.play
import org.bibletranslationtools.orature.resources.previousChunk
import org.bibletranslationtools.orature.resources.sourceAudio
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.components.OratureAudioPlayerRow
import org.bibletranslationtools.orature.ui.components.OraturePluginOpenedCover
import org.bibletranslationtools.orature.ui.components.PlaybackSpeedMenu
import org.bibletranslationtools.orature.ui.viewmodels.OratureChapterReviewViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * The Final Review step body (JVM: `ChapterReview`): the chapter's source audio on top, the compiled
 * chapter take as an editable verse-marker waveform in the center, and a transport with Add Marker,
 * seek prev/next marker, Play, and Next Chapter (enabled once every required marker is placed).
 */
@Composable
fun OratureChapterReviewScreen(viewModel: OratureChapterReviewViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // JVM: `.translation-view { -fx-background-color: -wa-foreground; }` — white, not the app's
    // light-gray page background (matches Blind Draft/Peer Edit).
    Box(modifier = Modifier.fillMaxSize().background(OratureColors.Foreground), contentAlignment = Alignment.Center) {
        when {
            uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
            !uiState.hasChapter -> Text(uiState.chapterTitle, color = OratureColors.RegularText)
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
            else -> ReviewBody(viewModel, uiState)
        }
    }
}

@Composable
private fun ReviewBody(
    viewModel: OratureChapterReviewViewModel,
    uiState: org.bibletranslationtools.orature.ui.viewmodels.OratureChapterReviewUiState
) {
    var frameTick by remember { mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) withFrameNanos { frameTick = it } }

    Column(modifier = Modifier.fillMaxSize()) {
        // Chapter title + source audio player (JVM: same `.blind-draft-section`-style source
        // player as Blind Draft — a scrub slider + playback-speed menu, not just a play button).
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                stringResource(Res.string.chapterTitle, stringResource(Res.string.chapter), uiState.chapterTitle),
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = OratureColors.RegularText
            )
            OratureAudioPlayerRow(
                isPlaying = uiState.isSourcePlaying,
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

        // Compiled chapter take waveform with editable verse markers.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            OratureSourceWaveform(
                waveformProvider = viewModel::currentWaveform,
                positionProvider = viewModel::currentPosition,
                totalFramesProvider = viewModel::currentTotalFrames,
                markers = uiState.markers,
                editable = true,
                onSeek = viewModel::seekToFrame,
                onClick = viewModel::pause,
                frameClock = { frameTick },
                onMoveMarker = viewModel::moveMarker,
                onDeleteMarker = viewModel::deleteMarker
            )
        }
        WaveformScrollbarReadOnly(
            positionProvider = viewModel::currentPosition,
            totalFramesProvider = viewModel::currentTotalFrames,
            onSeekToFrame = viewModel::seekToFrame,
            frameClock = { frameTick }
        )

        // Transport: Add Marker (split button), seek prev/next marker, Play, Next Chapter.
        Row(
            modifier = Modifier.fillMaxWidth().background(OratureColors.Foreground).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AddMarkerSplitButton(
                onAddVerseMarker = viewModel::placeMarker,
                canAddBookMarker = uiState.canAddBookMarker,
                onAddBookMarker = viewModel::addBookMarker,
                canAddChapterMarker = uiState.canAddChapterMarker,
                onAddChapterMarker = viewModel::addChapterMarker
            )
            Spacer(Modifier.weight(1f))
            TertiaryIconButton(onClick = viewModel::seekPrevious, contentDescription = stringResource(Res.string.previousChunk)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = OratureColors.RegularText)
            }
            TertiaryIconButton(onClick = viewModel::togglePlay, contentDescription = stringResource(Res.string.play)) {
                Icon(if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = OratureColors.RegularText)
            }
            TertiaryIconButton(onClick = viewModel::seekNext, contentDescription = stringResource(Res.string.nextChunk)) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, tint = OratureColors.RegularText)
            }
            Spacer(Modifier.size(12.dp))
            Button(
                onClick = viewModel::goToNextChapter,
                enabled = uiState.canGoNextChapter,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
            ) {
                Text(stringResource(Res.string.nextChapter))
                Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(18.dp))
            }
        }
    }
}

/**
 * JVM `.btn.btn--tertiary.btn--icon`: a rounded SQUARE icon button (12dp corners, not circular)
 * with a 2dp border — used for the bottom transport's seek-prev/play-pause/seek-next controls.
 */
@Composable
private fun TertiaryIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .border(2.dp, OratureColors.SurfaceTertiary, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private enum class MarkerKind { VERSE, BOOK, CHAPTER }

/**
 * The "Add Marker" split button (JVM: `AddMarkerSplitButton` + `AddMarkerMenu`): a primary action
 * fused to a small chevron-down trigger, both rounded only on their OUTER corners (JVM:
 * `chapter-selector__btn-prev`/`btn-next`) so they read as one connected pill. The trigger opens a
 * menu offering Verse / Book (chapter 1 only, once) / Chapter (every chapter, once) Marker —
 * selecting one only SWITCHES which kind the primary button places next; it does not place a
 * marker itself. Clicking the primary button places the currently-selected kind at the playhead.
 */
@Composable
private fun AddMarkerSplitButton(
    onAddVerseMarker: () -> Unit,
    canAddBookMarker: Boolean,
    onAddBookMarker: () -> Unit,
    canAddChapterMarker: Boolean,
    onAddChapterMarker: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(MarkerKind.VERSE) }

    // If the selected kind stops being available (already placed / no longer required), fall back
    // to Verse rather than leaving the primary button pointed at a dead option.
    androidx.compose.runtime.LaunchedEffect(canAddBookMarker, canAddChapterMarker) {
        if (selected == MarkerKind.BOOK && !canAddBookMarker) selected = MarkerKind.VERSE
        if (selected == MarkerKind.CHAPTER && !canAddChapterMarker) selected = MarkerKind.VERSE
    }

    val (label, icon, enabled, onClick) = when (selected) {
        MarkerKind.VERSE -> MarkerButtonSpec(stringResource(Res.string.addVerseMarker), Icons.Filled.Bookmark, true, onAddVerseMarker)
        MarkerKind.BOOK -> MarkerButtonSpec(stringResource(Res.string.addBookMarker), Icons.AutoMirrored.Filled.MenuBook, canAddBookMarker, onAddBookMarker)
        MarkerKind.CHAPTER -> MarkerButtonSpec(stringResource(Res.string.addChapterMarker), Icons.Filled.Description, canAddChapterMarker, onAddChapterMarker)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 0.dp, bottomEnd = 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, modifier = Modifier.padding(start = 6.dp))
        }
        Box {
            Button(
                onClick = { menuOpen = true },
                shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 12.dp, bottomEnd = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.addVerseMarker)) },
                    leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                    onClick = { selected = MarkerKind.VERSE; menuOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.addBookMarker)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                    enabled = canAddBookMarker,
                    onClick = { selected = MarkerKind.BOOK; menuOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.addChapterMarker)) },
                    leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    enabled = canAddChapterMarker,
                    onClick = { selected = MarkerKind.CHAPTER; menuOpen = false }
                )
            }
        }
    }
}

private data class MarkerButtonSpec(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val enabled: Boolean,
    val onClick: () -> Unit
)
