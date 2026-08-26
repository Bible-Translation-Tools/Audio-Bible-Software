package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.next
import org.bibletranslationtools.orature.resources.pause
import org.bibletranslationtools.orature.resources.record
import org.bibletranslationtools.orature.resources.edit
import org.bibletranslationtools.orature.resources.reRecord
import org.bibletranslationtools.orature.resources.resume
import org.bibletranslationtools.orature.resources.save
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureVerseItem
import org.bibletranslationtools.otter.common.domain.narration.teleprompter.TeleprompterItemState
import org.jetbrains.compose.resources.stringResource

/** Per-verse teleprompter actions (JVM: the NarrationTextCell events). */
class TeleprompterActions(
    val onRecord: (Int) -> Unit = {},
    val onNext: (Int) -> Unit = {},
    val onPauseRecording: (Int) -> Unit = {},
    val onResumeRecording: (Int) -> Unit = {},
    val onRecordAgain: (Int) -> Unit = {},
    val onSave: (Int) -> Unit = {},
    val onPlay: (Int) -> Unit = {},
    val onPausePlayback: (Int) -> Unit = {},
    /** Open a recorded verse in the configured external editor (desktop only). */
    val onEditExternally: (Int) -> Unit = {}
)

/**
 * Orature's narration teleprompter (JVM: `TeleprompterView` → `NarrationTextItem`): a white list
 * of verse rows. Each row is [ play/pause | verse number + text (centered, ≤820dp) | the
 * state-appropriate record buttons ]. The active verse's text is highlighted primary blue.
 *
 * [actionsEnabled] gates the record/play controls (false until playback/record land in 5b/5c).
 */
@Composable
fun OratureTeleprompter(
    verses: List<OratureVerseItem>,
    highlightedIndex: Int,
    actions: TeleprompterActions,
    actionsEnabled: Boolean,
    canEditExternally: Boolean = false,
    modifier: Modifier = Modifier
) {
    val lastVerseIndex = verses.lastOrNull { !it.isTitle }?.index
    val listState = rememberLazyListState()

    // Follow the row that owns the primary action, so a narrator can record a whole chapter without
    // chasing the button down the page (JVM: TeleprompterView scrolled to the active verse on every
    // change). Pressing Next hands the action to the next row and this brings it back to the same
    // place, which means the button lands under the pointer that just clicked it — rows put their
    // buttons at the TOP, so a fixed row top is a fixed button position however long the verse text.
    val activeIndex = verses.indexOfFirst { it.state in ACTIVE_ITEM_STATES }.takeIf { it >= 0 }
    LaunchedEffect(activeIndex) {
        if (activeIndex != null) listState.animateScrollToItem(activeIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(OratureColors.Foreground),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(verses, key = { it.index }) { verse ->
            VerseRow(
                verse = verse,
                isLast = verse.index == lastVerseIndex,
                highlighted = verse.index == highlightedIndex,
                actions = actions,
                actionsEnabled = actionsEnabled,
                canEditExternally = canEditExternally
            )
        }
    }
}

/**
 * The states in which a row owns the record/next/save action — see [VerseButtons], where exactly
 * these render a Record, or a Pause/Resume paired with Next/Save.
 *
 * PLAYING is deliberately absent, and it is the one that matters: playing back a finished verse
 * must not drag the page off the verse being recorded. The JVM got there differently, switching an
 * `autoScrollProperty` off in `play(verseIndex)` and back on when playback paused; keying off which
 * row holds the action needs no flag to keep in sync, and RECORD_AGAIN_ACTIVE means re-recording a
 * verse further up scrolls to it for free.
 */
private val ACTIVE_ITEM_STATES = setOf(
    TeleprompterItemState.BEGIN_RECORDING,
    TeleprompterItemState.RECORD,
    TeleprompterItemState.RECORD_ACTIVE,
    TeleprompterItemState.RECORDING_PAUSED,
    TeleprompterItemState.PLAYING_WHILE_RECORDING_PAUSED,
    TeleprompterItemState.RECORD_AGAIN_ACTIVE,
    TeleprompterItemState.RECORD_AGAIN_PAUSED
)

@Composable
private fun VerseRow(
    verse: OratureVerseItem,
    isLast: Boolean,
    highlighted: Boolean,
    actions: TeleprompterActions,
    actionsEnabled: Boolean,
    canEditExternally: Boolean
) {
    // The row content is a CENTERED, width-capped group (JVM `.narration-list__verse-item`
    // alignment center): on a wide window it leaves equal gaps left/right so the scripture text
    // stays readable, instead of stretching edge-to-edge.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(OratureColors.Foreground)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            modifier = Modifier.widthIn(max = 1260.dp).fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left: play / pause (tertiary icon button).
            PlayPauseButton(
                isPlaying = verse.state == TeleprompterItemState.PLAYING ||
                    verse.state == TeleprompterItemState.PLAYING_WHILE_RECORDING_PAUSED,
                enabled = actionsEnabled && (verse.isPlayEnabled ||
                    verse.state == TeleprompterItemState.PLAYING),
                onPlay = { actions.onPlay(verse.index) },
                onPause = { actions.onPausePlayback(verse.index) }
            )

            // Middle: verse number (superscript) + text. Title rows (book/chapter) show only
            // their name, no verse number.
            val bodyColor = if (highlighted) OratureColors.Primary else OratureColors.RegularText
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!verse.isTitle) {
                    Text(
                        text = verse.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = bodyColor
                    )
                }
                Text(
                    text = verse.text,
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                    fontWeight = if (verse.isTitle) FontWeight.SemiBold else FontWeight.Normal,
                    color = bodyColor,
                    modifier = Modifier.weight(1f)
                )
            }

            // Right: the record/next/save buttons for this verse's state.
            Box(modifier = Modifier.width(350.dp), contentAlignment = Alignment.TopCenter) {
                VerseButtons(verse, isLast, actions, actionsEnabled, canEditExternally)
            }
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit
) {
    val tint = if (enabled) OratureColors.RegularText else OratureColors.Disabled
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, OratureColors.SurfaceTertiary, RoundedCornerShape(12.dp))
            .background(OratureColors.Foreground)
            .then(
                if (enabled) Modifier .clickable { if (isPlaying) onPause() else onPlay() }
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun VerseButtons(
    verse: OratureVerseItem,
    isLast: Boolean,
    actions: TeleprompterActions,
    actionsEnabled: Boolean,
    canEditExternally: Boolean
) {
    val i = verse.index
    val nextOrSaveText = if (isLast) stringResource(Res.string.save) else stringResource(Res.string.next)
    val nextOrSaveIcon = if (isLast) Icons.Filled.CheckCircle else Icons.Filled.ArrowDownward
    val nextOrSave = { if (isLast) actions.onSave(i) else actions.onNext(i) }

    when (verse.state) {
        TeleprompterItemState.BEGIN_RECORDING,
        TeleprompterItemState.RECORD ->
            NarrationButton(
                stringResource(Res.string.record), Icons.Filled.Mic, NarrationButtonStyle.PRIMARY,
                onClick = { actions.onRecord(i) }, enabled = actionsEnabled, modifier = Modifier.width(336.dp)
            )

        TeleprompterItemState.RECORD_DISABLED ->
            NarrationButton(
                stringResource(Res.string.record), Icons.Filled.Mic, NarrationButtonStyle.PRIMARY,
                onClick = {}, enabled = false, modifier = Modifier.width(336.dp)
            )

        TeleprompterItemState.RECORD_ACTIVE -> ButtonPair {
            NarrationButton(stringResource(Res.string.pause), Icons.Filled.Pause, NarrationButtonStyle.SECONDARY,
                onClick = { actions.onPauseRecording(i) }, enabled = actionsEnabled, active = true, modifier = Modifier.width(160.dp))
            NarrationButton(nextOrSaveText, nextOrSaveIcon, NarrationButtonStyle.SECONDARY,
                onClick = nextOrSave, enabled = actionsEnabled, modifier = Modifier.width(160.dp))
        }

        TeleprompterItemState.RECORDING_PAUSED -> ButtonPair {
            NarrationButton(stringResource(Res.string.resume), Icons.Filled.Mic, NarrationButtonStyle.PRIMARY,
                onClick = { actions.onResumeRecording(i) }, enabled = actionsEnabled, modifier = Modifier.width(160.dp))
            NarrationButton(nextOrSaveText, nextOrSaveIcon, NarrationButtonStyle.SECONDARY,
                onClick = nextOrSave, enabled = actionsEnabled, modifier = Modifier.width(160.dp))
        }

        TeleprompterItemState.PLAYING_WHILE_RECORDING_PAUSED -> ButtonPair {
            NarrationButton(stringResource(Res.string.resume), Icons.Filled.Mic, NarrationButtonStyle.PRIMARY,
                onClick = {}, enabled = false, modifier = Modifier.width(160.dp))
            NarrationButton(nextOrSaveText, nextOrSaveIcon, NarrationButtonStyle.SECONDARY,
                onClick = {}, enabled = false, modifier = Modifier.width(160.dp))
        }

        TeleprompterItemState.RECORD_AGAIN,
        TeleprompterItemState.PLAYING ->
            if (canEditExternally) ButtonPair {
                NarrationButton(
                    stringResource(Res.string.reRecord), Icons.Filled.Mic, NarrationButtonStyle.SECONDARY,
                    onClick = { actions.onRecordAgain(i) },
                    enabled = actionsEnabled && verse.isRecordAgainEnabled, modifier = Modifier.width(160.dp)
                )
                NarrationButton(
                    stringResource(Res.string.edit), Icons.Filled.Edit, NarrationButtonStyle.SECONDARY,
                    onClick = { actions.onEditExternally(i) },
                    enabled = actionsEnabled && verse.isRecordAgainEnabled, modifier = Modifier.width(160.dp)
                )
            } else NarrationButton(
                stringResource(Res.string.reRecord), Icons.Filled.Mic, NarrationButtonStyle.SECONDARY,
                onClick = { actions.onRecordAgain(i) },
                enabled = actionsEnabled && verse.isRecordAgainEnabled, modifier = Modifier.width(336.dp)
            )

        TeleprompterItemState.RECORD_AGAIN_DISABLED ->
            NarrationButton(
                stringResource(Res.string.reRecord), Icons.Filled.Mic, NarrationButtonStyle.SECONDARY,
                onClick = {}, enabled = false, modifier = Modifier.width(336.dp)
            )

        TeleprompterItemState.RECORD_AGAIN_ACTIVE -> ButtonPair {
            NarrationButton(stringResource(Res.string.pause), Icons.Filled.Pause, NarrationButtonStyle.SECONDARY,
                onClick = { actions.onPauseRecording(i) }, enabled = actionsEnabled, active = true, modifier = Modifier.width(160.dp))
            NarrationButton(stringResource(Res.string.save), Icons.Filled.CheckCircle, NarrationButtonStyle.PRIMARY,
                onClick = { actions.onSave(i) }, enabled = actionsEnabled, modifier = Modifier.width(160.dp))
        }

        TeleprompterItemState.RECORD_AGAIN_PAUSED -> ButtonPair {
            NarrationButton(stringResource(Res.string.resume), Icons.Filled.Mic, NarrationButtonStyle.SECONDARY,
                onClick = { actions.onResumeRecording(i) }, enabled = actionsEnabled, modifier = Modifier.width(160.dp))
            NarrationButton(stringResource(Res.string.save), Icons.Filled.CheckCircle, NarrationButtonStyle.PRIMARY,
                onClick = { actions.onSave(i) }, enabled = actionsEnabled, modifier = Modifier.width(160.dp))
        }
    }
}

@Composable
private fun ButtonPair(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { content() }
}
