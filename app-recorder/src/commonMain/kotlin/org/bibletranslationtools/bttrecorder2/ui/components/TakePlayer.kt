package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_pause
import org.bibletranslationtools.shared.resources.action_play
import org.bibletranslationtools.shared.resources.cd_next_take
import org.bibletranslationtools.shared.resources.cd_open_in_playback
import org.bibletranslationtools.shared.resources.cd_previous_take
import org.bibletranslationtools.shared.resources.cd_select_take
import org.bibletranslationtools.shared.resources.unit_take_position
import org.jetbrains.compose.resources.stringResource

/** The design's star for a selected take; the theme has no orange role. */
private val SelectedStar = Color(0xFFE99A2E)

/**
 * A take's player for the project lists: transport, then delete / open in Playback / select (filled star
 * when [take] is the selected one), then which take of how many, with arrows that stop at either end.
 */
@Composable
internal fun TakePlayer(
    take: Take,
    position: Int,
    takeCount: Int,
    isSelected: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: () -> Unit,
    isPlaying: Boolean,
    playbackProgress: Float,
    elapsedText: String,
    durationText: String,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
    onOpenPlayback: () -> Unit,
    deleteDescription: String
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(Res.string.action_pause) else stringResource(Res.string.action_play),
                    modifier = Modifier.size(32.dp)
                )
            }
            ProgressBar(playbackProgress, modifier = Modifier.weight(1f))
            // One time : how long the take is, then how far in once it has started.
            Text(
                text = if (playbackProgress > 0f) elapsedText else durationText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.widthIn(max = 404.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = deleteDescription,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onOpenPlayback) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = stringResource(Res.string.cd_open_in_playback),
                    modifier = Modifier.size(36.dp)
                )
            }
            // Filled when this is the chapter's selected take; tapping an outline star selects it.
            IconButton(onClick = onSelect, enabled = !isSelected) {
                Icon(
                    if (isSelected) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(Res.string.cd_select_take),
                    tint = if (isSelected) SelectedStar else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, enabled = position > 1) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.cd_previous_take))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(Res.string.unit_take_position, position, takeCount),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    takeDateText(take.createdTimestamp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onNext, enabled = position < takeCount) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(Res.string.cd_next_take))
            }
        }
    }
}
