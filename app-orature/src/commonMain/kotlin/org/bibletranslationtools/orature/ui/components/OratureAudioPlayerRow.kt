package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.cancel
import org.bibletranslationtools.orature.resources.custom
import org.bibletranslationtools.orature.resources.customSpeedRate
import org.bibletranslationtools.orature.resources.pause
import org.bibletranslationtools.orature.resources.playSource
import org.bibletranslationtools.orature.resources.playbackSpeed
import org.bibletranslationtools.orature.resources.setCustom
import org.bibletranslationtools.orature.ui.OratureColors
import org.jetbrains.compose.resources.stringResource

/** JVM `SimpleAudioPlayer.playbackRateOptions`. */
private val PLAYBACK_RATE_OPTIONS = listOf(0.25, 0.50, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

/** "%.2fx" formatting (JVM: `String.format("%.2fx", speed)`). */
private fun formatRate(rate: Double): String {
    val hundredths = kotlin.math.round(rate * 100).toInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}x"
}

/**
 * A single audio-player row (JVM: `SimpleAudioPlayer`): a round play/pause button, then a column of
 * an optional title line ABOVE the slider (JVM: `titleTextProperty`, e.g. "Take 2" — unset for the
 * source row) and the scrub slider + trailing side label (JVM: `sideTextProperty`) below it. The
 * slider is interactive only when [isActive] — the shared player is currently loaded for this row.
 * [trailingContent] adds the row-level trailing control (the playback-speed menu for source; takes
 * pass none here — their export/delete/star icons are separate trailing siblings in `TakeRow`).
 *
 * Shared between the Blind Draft "source audio" row and Final Review's chapter-source player.
 */
@Composable
fun OratureAudioPlayerRow(
    isPlaying: Boolean,
    isActive: Boolean,
    positionMs: Int,
    durationMs: Int,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    sideLabel: String,
    title: String? = null,
    modifier: Modifier = Modifier,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    val fraction = if (isActive && durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // JVM: `.btn--tertiary` — a rounded SQUARE (12dp corners), not circular, with a 2dp border.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, OratureColors.SurfaceTertiary, RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) stringResource(Res.string.pause) else stringResource(Res.string.playSource),
                tint = OratureColors.RegularText80
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            title?.let {
                Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OratureColors.RegularText80)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = fraction,
                    onValueChange = onSeek,
                    enabled = isActive,
                    colors = SliderDefaults.colors(
                        thumbColor = OratureColors.Primary,
                        activeTrackColor = OratureColors.Primary,
                        inactiveTrackColor = OratureColors.SurfaceTertiary,
                        disabledThumbColor = OratureColors.Primary,
                        disabledActiveTrackColor = OratureColors.Primary,
                        disabledInactiveTrackColor = OratureColors.SurfaceTertiary
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(sideLabel, fontSize = 13.sp, color = OratureColors.RegularText80, modifier = Modifier.padding(start = 6.dp))
            }
        }
        trailingContent()
    }
}

/**
 * The playback-speed menu button (JVM: `WaMenuButton` on `SimpleAudioPlayer` — a speedometer icon +
 * a small arrow, opening a list of rate presets, plus a "Custom" rate sub-panel).
 */
@Composable
fun PlaybackSpeedMenu(rate: Double, onRateSelected: (Double) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // JVM: WaMenuButton swaps its whole item list between the preset menu and a "Custom" rate
    // slider sub-menu (createPlaybackRateMenu / createCustomRateMenu); mirrored here as one of
    // two panels inside the same DropdownMenu, toggled by `showCustom`.
    var showCustom by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier.clickable { expanded = true }.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(Icons.Filled.Speed, contentDescription = stringResource(Res.string.playbackSpeed), tint = OratureColors.RegularText, modifier = Modifier.size(20.dp))
            Icon(Icons.Filled.ArrowDropUp, contentDescription = null, tint = OratureColors.RegularText, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; showCustom = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            if (showCustom) {
                CustomRateContent(
                    initialRate = rate,
                    onCancel = { showCustom = false },
                    onSetCustom = { newRate ->
                        onRateSelected(newRate)
                        expanded = false
                        showCustom = false
                    }
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(Res.string.playbackSpeed),
                        fontWeight = FontWeight.Bold,
                        color = OratureColors.RegularText,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showCustom = true }) {
                        Text(stringResource(Res.string.custom))
                    }
                }
                for (option in PLAYBACK_RATE_OPTIONS) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                formatRate(option),
                                fontWeight = if (option == rate) FontWeight.Bold else FontWeight.Normal,
                                color = if (option == rate) OratureColors.Primary else OratureColors.RegularText
                            )
                        },
                        onClick = { onRateSelected(option); expanded = false }
                    )
                }
                // A rate set via the Custom slider that isn't one of the presets stays selectable
                // as its own item (JVM: appends a "Custom (0.60x)" item when the current speed
                // isn't in playbackRateOptions).
                if (rate !in PLAYBACK_RATE_OPTIONS) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(Res.string.customSpeedRate, stringResource(Res.string.custom), formatRate(rate)),
                                fontWeight = FontWeight.Bold,
                                color = OratureColors.Primary
                            )
                        },
                        onClick = { onRateSelected(rate); expanded = false }
                    )
                }
            }
        }
    }
}

/**
 * The "Custom" rate sub-panel (JVM: `createCustomRateMenu`): a header (title + Cancel), a slider
 * spanning the preset range (0.25x–2.0x), a large centered live-updating "%.2fx" label, and a
 * full-width "Set Custom" button that commits the rate and closes the menu.
 */
@Composable
private fun CustomRateContent(
    initialRate: Double,
    onCancel: () -> Unit,
    onSetCustom: (Double) -> Unit
) {
    var sliderValue by remember { mutableStateOf(initialRate.toFloat()) }
    Column(
        modifier = Modifier.width(320.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.custom),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = OratureColors.RegularText,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(12.dp)) { Text(stringResource(Res.string.cancel)) }
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = PLAYBACK_RATE_OPTIONS.first().toFloat()..PLAYBACK_RATE_OPTIONS.last().toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = OratureColors.Primary,
                activeTrackColor = OratureColors.Primary,
                inactiveTrackColor = OratureColors.SurfaceTertiary
            )
        )
        Text(
            formatRate(sliderValue.toDouble()),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = OratureColors.RegularText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = { onSetCustom(sliderValue.toDouble()) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(Res.string.setCustom))
        }
    }
}
