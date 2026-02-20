package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.theme.TranslationRecorderTheme
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.PlaybackViewModel

@Composable
fun PlaybackScreen(
    viewModel: PlaybackViewModel,
    onBackClick: () -> Unit
) {
    val ui by viewModel.uiState.collectAsState()
    var waveformWidth by remember { mutableStateOf(0) }

    LaunchedEffect(waveformWidth) {
        if (waveformWidth > 0) {
            viewModel.setWaveformWidth(waveformWidth)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.cleanup()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF202020))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TranslationRecorderTheme.veryDarkGray1)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "${ui.targetUi.sourceLabel}  ${ui.targetUi.bookLabel}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.width(12.dp))
            PlaybackStepper(
                label = "Chapter",
                value = ui.targetUi.chapterValue,
                minusEnabled = ui.targetUi.canGoPreviousChapter,
                plusEnabled = ui.targetUi.canGoNextChapter,
                onMinus = viewModel::goPreviousChapter,
                onPlus = viewModel::goNextChapter
            )
            Spacer(Modifier.width(12.dp))
            PlaybackStepper(
                label = "Verse",
                value = ui.targetUi.unitValue,
                minusEnabled = ui.targetUi.canGoPreviousUnit,
                plusEnabled = ui.targetUi.canGoNextUnit,
                onMinus = viewModel::goPreviousUnit,
                onPlus = viewModel::goNextUnit
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { waveformWidth = it.width }
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            PlaybackWaveform(
                samples = ui.waveformSamples,
                modifier = Modifier.fillMaxSize()
            )
            if (ui.error != null) {
                Text(
                    text = ui.error ?: "",
                    color = TranslationRecorderTheme.strongRed,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(TranslationRecorderTheme.darkGray0)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { viewModel.showMinimap(true) },
                shape = RoundedCornerShape(4.dp),
                enabled = !ui.showMinimap
            ) {
                Text("Minimap")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.showMinimap(false) },
                shape = RoundedCornerShape(4.dp),
                enabled = ui.showMinimap
            ) {
                Text("Source")
            }
            Spacer(Modifier.width(12.dp))
            if (ui.showMinimap) {
                Slider(
                    value = ui.progress,
                    onValueChange = viewModel::seekToProgress,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = if (ui.sourceAudioAvailable) "Source audio available" else "No source audio",
                    color = TranslationRecorderTheme.gray0
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(TranslationRecorderTheme.blue)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${ui.elapsedText} / ${ui.durationText}",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::seekToPreviousCue) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous cue", tint = Color.White)
                }
                IconButton(onClick = viewModel::togglePlayPause) {
                    Icon(
                        if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play pause",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
                IconButton(onClick = viewModel::seekToNextCue) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next cue", tint = Color.White)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::selectPreviousTake) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous take", tint = Color.White)
                }
                Text(
                    text = ui.selectedTake?.let { "Take ${it.number}" } ?: "No take",
                    color = Color.White
                )
                IconButton(onClick = viewModel::selectNextTake) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next take", tint = Color.White)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(onClick = {}) {
                Text("Edit (new take default)")
            }
        }
    }
}

@Composable
private fun PlaybackStepper(
    label: String,
    value: String,
    minusEnabled: Boolean,
    plusEnabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onMinus,
            enabled = minusEnabled,
            modifier = Modifier.size(width = 36.dp, height = 36.dp)
        ) {
            Text("-")
        }
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .height(36.dp)
                .width(44.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(value, color = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(
            onClick = onPlus,
            enabled = plusEnabled,
            modifier = Modifier.size(width = 36.dp, height = 36.dp)
        ) {
            Text("+")
        }
    }
}

@Composable
private fun PlaybackWaveform(
    samples: FloatArray,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawRect(Color.Black)
        val width = size.width.toInt().coerceAtLeast(1)
        val midY = size.height / 2f
        val scale = size.height / 2f / 32768f
        val maxX = minOf(width, samples.size / 2)

        for (x in 0 until maxX) {
            val idx = x * 2
            val minRaw = samples[idx]
            val maxRaw = samples[idx + 1]
            val y1 = midY - (minRaw * scale)
            val y2 = midY - (maxRaw * scale)

            drawLine(
                color = Color.White,
                start = Offset(x.toFloat(), y1),
                end = Offset(x.toFloat(), y2)
            )
        }

        drawLine(
            color = Color(0xFF13C4C3),
            start = Offset(0f, midY),
            end = Offset(size.width, midY)
        )
        drawLine(
            color = Color(0xFF1EA7FD),
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height)
        )
    }
}
