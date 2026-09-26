package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.EditionChangeState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.EditionChangeViewModel
import org.bibletranslationtools.otter.common.domain.collections.ChapterOutcome
import org.bibletranslationtools.otter.common.domain.collections.ChapterUpgradePlan
import org.bibletranslationtools.otter.common.domain.collections.EditionChoice
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseGroup
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseRange
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_apply
import org.bibletranslationtools.shared.resources.action_back
import org.bibletranslationtools.shared.resources.action_cancel
import org.bibletranslationtools.shared.resources.action_close
import org.bibletranslationtools.shared.resources.action_next
import org.bibletranslationtools.shared.resources.edition_applying
import org.bibletranslationtools.shared.resources.edition_change_added
import org.bibletranslationtools.shared.resources.edition_change_current
import org.bibletranslationtools.shared.resources.edition_change_failed
import org.bibletranslationtools.shared.resources.edition_change_hint
import org.bibletranslationtools.shared.resources.edition_change_merged
import org.bibletranslationtools.shared.resources.edition_change_moved_in
import org.bibletranslationtools.shared.resources.edition_change_moved_out
import org.bibletranslationtools.shared.resources.edition_change_no_other
import org.bibletranslationtools.shared.resources.edition_change_regrouped
import org.bibletranslationtools.shared.resources.edition_change_removed
import org.bibletranslationtools.shared.resources.edition_change_renumbered
import org.bibletranslationtools.shared.resources.edition_change_split
import org.bibletranslationtools.shared.resources.edition_change_title
import org.bibletranslationtools.shared.resources.edition_done_message
import org.bibletranslationtools.shared.resources.edition_done_title
import org.bibletranslationtools.shared.resources.edition_held_back_message
import org.bibletranslationtools.shared.resources.edition_outcome_added
import org.bibletranslationtools.shared.resources.edition_outcome_adopts
import org.bibletranslationtools.shared.resources.edition_outcome_chunks_reset
import org.bibletranslationtools.shared.resources.edition_outcome_held_back
import org.bibletranslationtools.shared.resources.edition_outcome_missing
import org.bibletranslationtools.shared.resources.edition_outcome_text_only
import org.bibletranslationtools.shared.resources.edition_preview_intro
import org.bibletranslationtools.shared.resources.edition_preview_nothing
import org.bibletranslationtools.shared.resources.edition_preview_title
import org.bibletranslationtools.shared.resources.label_newer
import org.bibletranslationtools.shared.resources.label_older
import org.bibletranslationtools.shared.resources.main_chapter_label
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform.getKoin

/**
 * Moves project book [projectBookId] to another installed edition of its source, up or down:
 * choose, preview each chapter's outcome, apply. [onChanged] runs once the change is applied, so
 * the caller can reload what it shows.
 */
@Composable
fun EditionChangeDialog(
    projectBookId: Int,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    // A fresh flow each time the dialog opens; the saved id keeps it across a configuration change.
    val opening = rememberSaveable { Random.nextLong() }
    val viewModel = viewModel(key = "edition-change-$projectBookId-$opening") {
        getKoin().get<EditionChangeViewModel> { parametersOf(projectBookId) }
    }
    val state by viewModel.state.collectAsState()
    val busy = state is EditionChangeState.Loading || state is EditionChangeState.Planning || state is EditionChangeState.Applying
    val finish = {
        if (state is EditionChangeState.Done) onChanged()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) finish() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = {
            Text(
                when (val s = state) {
                    is EditionChangeState.Previewing ->
                        stringResource(Res.string.edition_preview_title, choiceText(s.choice))
                    is EditionChangeState.Done -> stringResource(Res.string.edition_done_title)
                    else -> stringResource(Res.string.edition_change_title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (val s = state) {
                    is EditionChangeState.Loading, is EditionChangeState.Planning -> Busy(null)
                    is EditionChangeState.Applying -> Busy(stringResource(Res.string.edition_applying))
                    is EditionChangeState.Choosing -> Choose(s, viewModel::select)
                    is EditionChangeState.Previewing -> Preview(s.plan.chapters)
                    is EditionChangeState.Done -> {
                        Text(stringResource(Res.string.edition_done_message, choiceText(s.choice)))
                        if (s.heldBack.isNotEmpty()) {
                            Spacer(Modifier.padding(top = 8.dp))
                            Text(stringResource(Res.string.edition_held_back_message, s.heldBack.joinToString(", ")))
                        }
                    }
                    is EditionChangeState.Error -> Text(
                        stringResource(Res.string.edition_change_failed, s.message),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is EditionChangeState.Choosing -> TextButton(
                    onClick = viewModel::preview,
                    enabled = s.selected != null
                ) { Text(stringResource(Res.string.action_next)) }
                is EditionChangeState.Previewing -> TextButton(onClick = viewModel::apply) {
                    Text(stringResource(Res.string.action_apply))
                }
                is EditionChangeState.Done, is EditionChangeState.Error -> TextButton(onClick = finish) {
                    Text(stringResource(Res.string.action_close))
                }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is EditionChangeState.Choosing -> TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
                is EditionChangeState.Previewing -> TextButton(onClick = viewModel::back) {
                    Text(stringResource(Res.string.action_back))
                }
                else -> Unit
            }
        }
    )
}

@Composable
private fun Busy(message: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        message?.let {
            Spacer(Modifier.width(12.dp))
            Text(it)
        }
    }
}

@Composable
private fun Choose(state: EditionChangeState.Choosing, onSelect: (EditionChoice) -> Unit) {
    Text(
        stringResource(Res.string.edition_change_current, sourceEditionText(state.book.current, state.book.currentCode)),
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.padding(top = 8.dp))
    if (state.book.choices.isEmpty()) {
        Text(stringResource(Res.string.edition_change_no_other))
        return
    }
    Text(
        stringResource(Res.string.edition_change_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Column(modifier = Modifier.selectableGroup().padding(top = 4.dp)) {
        state.book.choices.forEach { choice ->
            val selected = state.selected?.edition?.id == choice.edition.id
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.RadioButton) { onSelect(choice) }
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(choiceText(choice))
                    Text(
                        stringResource(if (choice.newer) Res.string.label_newer else Res.string.label_older),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Preview(chapters: List<ChapterUpgradePlan>) {
    val changing = chapters.filter { it.outcome != ChapterOutcome.UNCHANGED }
    if (changing.isEmpty()) {
        Text(stringResource(Res.string.edition_preview_nothing))
        return
    }
    Text(stringResource(Res.string.edition_preview_intro))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        changing.forEach { chapter ->
            Column {
                Text(
                    stringResource(Res.string.main_chapter_label, chapter.sort.toString()),
                    fontWeight = FontWeight.SemiBold
                )
                Text(outcomeText(chapter), style = MaterialTheme.typography.bodyMedium)
                if (chapter.outcome == ChapterOutcome.ADOPTS || chapter.outcome == ChapterOutcome.HELD_BACK) {
                    chapter.structuralChanges.forEach { group ->
                        Text(
                            "• " + changeText(group),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (chapter.chunksReset) {
                    Text(
                        stringResource(Res.string.edition_outcome_chunks_reset),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun outcomeText(chapter: ChapterUpgradePlan): String = when (chapter.outcome) {
    ChapterOutcome.TEXT_ONLY -> stringResource(Res.string.edition_outcome_text_only, chapter.textChangedVerses.size)
    ChapterOutcome.ADOPTS -> stringResource(Res.string.edition_outcome_adopts)
    ChapterOutcome.HELD_BACK ->
        if (chapter.diff == null) stringResource(Res.string.edition_outcome_missing)
        else stringResource(Res.string.edition_outcome_held_back)
    ChapterOutcome.ADDED -> stringResource(Res.string.edition_outcome_added)
    ChapterOutcome.UNCHANGED -> ""
}

@Composable
private fun changeText(group: VerseGroup): String {
    val from = group.from.ranges()
    val to = group.to.ranges()
    return when (group.change) {
        VerseChange.MERGED -> stringResource(Res.string.edition_change_merged, from, to)
        VerseChange.SPLIT -> stringResource(Res.string.edition_change_split, from, to)
        VerseChange.REGROUPED -> stringResource(Res.string.edition_change_regrouped, from, to)
        VerseChange.RENUMBERED -> stringResource(Res.string.edition_change_renumbered, from, to)
        VerseChange.ADDED -> stringResource(Res.string.edition_change_added, to)
        VerseChange.REMOVED -> stringResource(Res.string.edition_change_removed, from)
        VerseChange.MOVED_OUT -> stringResource(
            Res.string.edition_change_moved_out, from, group.movedTo?.chapterSlug?.chapterNumber().orEmpty()
        )
        VerseChange.MOVED_IN -> stringResource(
            Res.string.edition_change_moved_in, to, group.movedFrom?.chapterSlug?.chapterNumber().orEmpty()
        )
        VerseChange.IDENTICAL, VerseChange.TEXT_CHANGED -> ""
    }
}

private fun List<VerseRange>.ranges() = joinToString(", ")

/** Chapter slugs end in the chapter number, e.g. `act_20`. */
private fun String.chapterNumber() = substringAfterLast('_').trimStart('0').ifEmpty { this }

@Composable
private fun choiceText(choice: EditionChoice) = sourceEditionText(choice.edition, choice.distinguishingCode)
