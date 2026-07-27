package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.next
import org.bibletranslationtools.orature.resources.pause
import org.bibletranslationtools.orature.resources.playAll
import org.bibletranslationtools.orature.resources.previous
import org.bibletranslationtools.orature.ui.OratureColors
import org.jetbrains.compose.resources.stringResource

/**
 * The narration transport bar (JVM: `NarrationToolBar` / `.narration-toolbar`): a white bar with
 * a bottom hairline border, left-aligned play/pause (secondary) + previous/next (tertiary),
 * padding 12×16, 8dp spacing. Controls are [enabled] only once playback is wired (5b).
 */
@Composable
fun OratureNarrationToolBar(
    isPlaying: Boolean,
    enabled: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OratureColors.Foreground)
            .border(width = 1.dp, color = OratureColors.SurfaceTertiary)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play/pause — Orature's secondary button with a label.
        PlayControl(isPlaying = isPlaying, enabled = enabled, onClick = onPlayPause)
        ToolbarIcon(Icons.Filled.SkipPrevious, stringResource(Res.string.previous), enabled, onPrevious)
        ToolbarIcon(Icons.Filled.SkipNext, stringResource(Res.string.next), enabled, onNext)
    }
}

@Composable
private fun PlayControl(isPlaying: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = if (isPlaying) stringResource(Res.string.pause) else stringResource(Res.string.playAll)
    val contentColor = if (enabled) OratureColors.Primary else OratureColors.Disabled
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, if (enabled) OratureColors.Primary else OratureColors.Disabled, RoundedCornerShape(12.dp))
            .background(OratureColors.Foreground)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp)
        )
        Text(label, color = contentColor, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) OratureColors.RegularText else OratureColors.Disabled
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, OratureColors.SurfaceTertiary, RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
    }
}
