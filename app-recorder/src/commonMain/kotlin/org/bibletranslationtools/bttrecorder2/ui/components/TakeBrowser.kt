package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_pause
import org.bibletranslationtools.shared.resources.action_play
import org.bibletranslationtools.shared.resources.cd_next_take
import org.bibletranslationtools.shared.resources.cd_open_in_playback
import org.bibletranslationtools.shared.resources.cd_previous_take
import org.bibletranslationtools.shared.resources.cd_select_take
import org.bibletranslationtools.shared.resources.take_count_short
import org.bibletranslationtools.shared.resources.unit_take_position
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val GreenPlay = Color(0xFF4CAF50)

/**
 * The expanded panel of a card with takes (a verse, or a chapter): play the take being viewed,
 * delete it, open it in playback, select it, and step through the others. The take viewed is the
 * card's to track; selecting it is what makes it the one used.
 */
@Composable
fun TakeBrowserPanel(
    takes: List<Take>,
    currentIndex: Int,
    selectedTake: Take?,
    isPlaying: Boolean,
    playbackProgress: Float,
    elapsedText: String,
    durationText: String,
    deleteDescription: StringResource,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
    onOpenPlayback: () -> Unit,
    onSelectTake: () -> Unit,
    onCycle: (Int) -> Unit
) {
    val currentTake = takes.getOrNull(currentIndex) ?: return
    HorizontalDivider()

    // Seekbar row
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = elapsedText,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurface
        )
        Slider(
            value = playbackProgress.coerceIn(0f, 1f),
            onValueChange = {},
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = GreenPlay,
                activeTrackColor = GreenPlay,
                inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        )
        Text(
            text = durationText,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    // Action row: delete | waveform | play | select-take
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(deleteDescription),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        IconButton(onClick = onOpenPlayback) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = stringResource(Res.string.cd_open_in_playback),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        // Play/pause is larger and green
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) stringResource(Res.string.action_pause) else stringResource(Res.string.action_play),
                tint = GreenPlay,
                modifier = Modifier.size(36.dp)
            )
        }

        IconButton(onClick = onSelectTake) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(Res.string.cd_select_take),
                tint = if (currentTake == selectedTake)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }

    // Footer — grey background, "Take X of Y" + date
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 2.dp)
    ) {
        IconButton(onClick = { onCycle(-1) }) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = stringResource(Res.string.cd_previous_take))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(Res.string.unit_take_position, currentIndex + 1, takes.size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal
            )
            val dateText = runCatching {
                // The device locale's own long date, e.g. "October 7, 2026" or "7 octobre 2026".
                currentTake.createdTimestamp.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
            }.getOrDefault("")
            if (dateText.isNotEmpty()) {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(onClick = { onCycle(1) }) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = stringResource(Res.string.cd_next_take))
        }
    }
}

/** How many takes a card has, beside its title. */
@Composable
fun TakeCount(count: Int) {
    Text(
        text = stringResource(Res.string.take_count_short, count),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}
