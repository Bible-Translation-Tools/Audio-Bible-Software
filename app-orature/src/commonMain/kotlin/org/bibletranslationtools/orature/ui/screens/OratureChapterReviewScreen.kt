package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.addVerseMarker
import org.bibletranslationtools.orature.resources.nextChapter
import org.bibletranslationtools.orature.resources.nextChunk
import org.bibletranslationtools.orature.resources.pause
import org.bibletranslationtools.orature.resources.play
import org.bibletranslationtools.orature.resources.playSource
import org.bibletranslationtools.orature.resources.previousChunk
import org.bibletranslationtools.orature.ui.OratureColors
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

    Box(modifier = Modifier.fillMaxSize().background(OratureColors.Background), contentAlignment = Alignment.Center) {
        when {
            uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
            !uiState.hasChapter -> Text(uiState.chapterTitle, color = OratureColors.RegularText)
            uiState.error != null -> Text(uiState.error!!, color = OratureColors.RegularText)
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
        // Chapter title + source audio player.
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(uiState.chapterTitle, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = OratureColors.RegularText)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::toggleSource) {
                Icon(if (uiState.isSourcePlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                Text(
                    if (uiState.isSourcePlaying) stringResource(Res.string.pause) else stringResource(Res.string.playSource),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

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

        // Transport: Add Marker, seek prev/next marker, Play, Next Chapter.
        Row(
            modifier = Modifier.fillMaxWidth().background(OratureColors.Foreground).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = viewModel::placeMarker,
                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(Res.string.addVerseMarker), modifier = Modifier.padding(start = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::seekPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(Res.string.previousChunk), tint = OratureColors.RegularText)
            }
            IconButton(onClick = viewModel::togglePlay) {
                Icon(if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = stringResource(Res.string.play), tint = OratureColors.RegularText)
            }
            IconButton(onClick = viewModel::seekNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(Res.string.nextChunk), tint = OratureColors.RegularText)
            }
            Spacer(Modifier.size(12.dp))
            Button(
                onClick = viewModel::goToNextChapter,
                enabled = uiState.canGoNextChapter,
                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
            ) {
                Text(stringResource(Res.string.nextChapter))
                Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(18.dp))
            }
        }
    }
}
