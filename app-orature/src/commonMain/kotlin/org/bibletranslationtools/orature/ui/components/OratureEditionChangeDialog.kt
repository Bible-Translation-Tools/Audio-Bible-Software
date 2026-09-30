package org.bibletranslationtools.orature.ui.components

import org.bibletranslationtools.orature.resources.listItemBullet
import org.bibletranslationtools.otter.common.domain.collections.EditionRelation
import org.bibletranslationtools.orature.resources.sameDateEdition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.apply
import org.bibletranslationtools.orature.resources.cancel
import org.bibletranslationtools.orature.resources.chapterNumber
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.editionApplying
import org.bibletranslationtools.orature.resources.editionChangeAdded
import org.bibletranslationtools.orature.resources.editionChangeFailed
import org.bibletranslationtools.orature.resources.editionChangeHint
import org.bibletranslationtools.orature.resources.editionChangeMerged
import org.bibletranslationtools.orature.resources.editionChangeMovedIn
import org.bibletranslationtools.orature.resources.editionChangeMovedOut
import org.bibletranslationtools.orature.resources.editionChangeRegrouped
import org.bibletranslationtools.orature.resources.editionChangeRemoved
import org.bibletranslationtools.orature.resources.editionChangeRenumbered
import org.bibletranslationtools.orature.resources.editionChangeSplit
import org.bibletranslationtools.orature.resources.editionChangeTitle
import org.bibletranslationtools.orature.resources.editionChecking
import org.bibletranslationtools.orature.resources.editionCurrent
import org.bibletranslationtools.orature.resources.editionDoneMessage
import org.bibletranslationtools.orature.resources.editionDoneTitle
import org.bibletranslationtools.orature.resources.editionHeldBackBook
import org.bibletranslationtools.orature.resources.editionNoOther
import org.bibletranslationtools.orature.resources.editionOutcomeAdded
import org.bibletranslationtools.orature.resources.editionOutcomeAdopts
import org.bibletranslationtools.orature.resources.editionOutcomeChunksReset
import org.bibletranslationtools.orature.resources.editionOutcomeHeldBack
import org.bibletranslationtools.orature.resources.editionOutcomeMissing
import org.bibletranslationtools.orature.resources.editionOutcomeTextOnly
import org.bibletranslationtools.orature.resources.editionPreviewBooks
import org.bibletranslationtools.orature.resources.editionPreviewNothing
import org.bibletranslationtools.orature.resources.editionPreviewTitle
import org.bibletranslationtools.orature.resources.editionPreviewUnavailable
import org.bibletranslationtools.orature.resources.editionPreviewWordingOnly
import org.bibletranslationtools.orature.resources.goBack
import org.bibletranslationtools.orature.resources.mixedEditions
import org.bibletranslationtools.orature.resources.newerEdition
import org.bibletranslationtools.orature.resources.next
import org.bibletranslationtools.orature.resources.olderEdition
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureEditionChangeState
import org.bibletranslationtools.orature.ui.viewmodels.OratureEditionChangeTarget
import org.bibletranslationtools.orature.ui.viewmodels.OratureEditionChangeViewModel
import org.bibletranslationtools.otter.common.domain.collections.BookUpgradePlan
import org.bibletranslationtools.otter.common.domain.collections.ChapterOutcome
import org.bibletranslationtools.otter.common.domain.collections.ChapterUpgradePlan
import org.bibletranslationtools.otter.common.domain.collections.EditionChoice
import org.bibletranslationtools.otter.common.domain.collections.ProjectUpgradePlan
import org.bibletranslationtools.otter.common.domain.collections.UpgradeProjectEdition
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseGroup
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseRange
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform.getKoin
import kotlin.random.Random

/**
 * Moves a project (every book) or one book to another installed edition of its source, up or
 * down: choose, preview, apply (O1-Q1). [onChanged] runs once a change is applied, so the home
 * screen can reload.
 */
@Composable
fun OratureEditionChangeDialog(
    target: OratureEditionChangeTarget,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    // A fresh flow each time the dialog opens; the saved id keeps it across a configuration change.
    val opening = rememberSaveable { Random.nextLong() }
    val vm = viewModel(key = "edition-change-$opening") {
        OratureEditionChangeViewModel(target, getKoin().get<UpgradeProjectEdition>())
    }
    val state by vm.state.collectAsState()
    val busy = state is OratureEditionChangeState.Loading ||
        state is OratureEditionChangeState.Planning ||
        state is OratureEditionChangeState.Applying
    val finish = {
        if (state is OratureEditionChangeState.Done) onChanged()
        onDismiss()
    }

    Dialog(
        onDismissRequest = { if (!busy) finish() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy)
    ) {
        Surface(modifier = Modifier.width(640.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (val s = state) {
                            is OratureEditionChangeState.Previewing ->
                                stringResource(Res.string.editionPreviewTitle, choiceText(s.choice))
                            is OratureEditionChangeState.Done -> stringResource(Res.string.editionDoneTitle)
                            else -> stringResource(Res.string.editionChangeTitle)
                        },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = OratureColors.RegularText,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = finish, enabled = !busy) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.close), tint = OratureColors.RegularText)
                    }
                }
                HorizontalDivider(color = OratureColors.SurfaceTertiary)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (val s = state) {
                        is OratureEditionChangeState.Loading -> Progress(null, null)
                        is OratureEditionChangeState.Planning ->
                            Progress(stringResource(Res.string.editionChecking, s.checked, s.total), fraction(s.checked, s.total))
                        is OratureEditionChangeState.Applying ->
                            Progress(stringResource(Res.string.editionApplying, s.done, s.total), fraction(s.done, s.total))
                        is OratureEditionChangeState.Choosing -> Choose(target, s, vm::select)
                        is OratureEditionChangeState.Previewing -> Preview(target, s.plan)
                        is OratureEditionChangeState.Done -> Done(target, s.choice, s.plan)
                        is OratureEditionChangeState.Error -> Text(
                            stringResource(Res.string.editionChangeFailed, s.message.orEmpty()),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                HorizontalDivider(color = OratureColors.SurfaceTertiary)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    when (val s = state) {
                        is OratureEditionChangeState.Choosing -> {
                            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text(stringResource(Res.string.cancel)) }
                            PrimaryButton(stringResource(Res.string.next), enabled = s.selected != null, onClick = vm::preview)
                        }
                        is OratureEditionChangeState.Previewing -> {
                            OutlinedButton(onClick = vm::back, shape = RoundedCornerShape(12.dp)) { Text(stringResource(Res.string.goBack)) }
                            PrimaryButton(stringResource(Res.string.apply), enabled = s.plan.books.isNotEmpty(), onClick = vm::apply)
                        }
                        is OratureEditionChangeState.Done, is OratureEditionChangeState.Error ->
                            PrimaryButton(stringResource(Res.string.close), enabled = true, onClick = finish)
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
    ) { Text(label) }
}

private fun fraction(done: Int, total: Int) = if (total > 0) done.toFloat() / total else 0f

@Composable
private fun Progress(message: String?, fraction: Float?) {
    if (fraction == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = OratureColors.Primary)
    } else {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = OratureColors.Primary)
    }
    message?.let { Text(it, fontSize = 14.sp, color = OratureColors.NoteText) }
}

@Composable
private fun currentText(target: OratureEditionChangeTarget): String {
    val editions = target.editions.distinctBy { it.id }
    return if (editions.size == 1) sourceEditionText(editions.single()) else stringResource(Res.string.mixedEditions)
}

@Composable
private fun Choose(
    target: OratureEditionChangeTarget,
    state: OratureEditionChangeState.Choosing,
    onSelect: (EditionChoice) -> Unit
) {
    Text(stringResource(Res.string.editionCurrent, currentText(target)), fontWeight = FontWeight.SemiBold, color = OratureColors.RegularText)
    if (state.choices.isEmpty()) {
        Text(stringResource(Res.string.editionNoOther), color = OratureColors.RegularText)
        return
    }
    Text(stringResource(Res.string.editionChangeHint), fontSize = 14.sp, color = OratureColors.NoteText)
    Column(modifier = Modifier.selectableGroup()) {
        state.choices.forEach { choice ->
            val selected = state.selected?.edition?.id == choice.edition.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(choice) })
                    .background(if (selected) OratureColors.PrimaryLight else MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = OratureColors.Primary))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(choiceText(choice), color = OratureColors.RegularText)
                    Text(
                        stringResource(
                            when (choice.relation) {
                                EditionRelation.NEWER -> Res.string.newerEdition
                                EditionRelation.OLDER -> Res.string.olderEdition
                                EditionRelation.SAME_DATES -> Res.string.sameDateEdition
                            }
                        ),
                        fontSize = 12.sp,
                        color = OratureColors.NoteText
                    )
                }
            }
        }
    }
}

/** Books whose verse structure changes somewhere, or that gain chapters or lose chunks. */
private fun BookUpgradePlan.notable(): List<ChapterUpgradePlan> =
    chapters.filter { it.outcome in NOTABLE || it.chunksReset }

private val NOTABLE = setOf(ChapterOutcome.ADOPTS, ChapterOutcome.HELD_BACK, ChapterOutcome.ADDED)

@Composable
private fun Preview(target: OratureEditionChangeTarget, plan: ProjectUpgradePlan) {
    if (plan.books.isEmpty() || plan.books.all { book -> book.chapters.all { it.outcome == ChapterOutcome.UNCHANGED } }) {
        Text(stringResource(Res.string.editionPreviewNothing), color = OratureColors.RegularText)
    }
    if (target.singleBook) {
        plan.books.forEach { book ->
            book.chapters.filter { it.outcome != ChapterOutcome.UNCHANGED }.forEach { ChapterLine(it) }
        }
    } else if (plan.books.isNotEmpty()) {
        Text(stringResource(Res.string.editionPreviewBooks, plan.books.size), color = OratureColors.RegularText)
        val wordingOnly = plan.books.count { book -> book.notable().isEmpty() && book.chapters.any { it.outcome == ChapterOutcome.TEXT_ONLY } }
        if (wordingOnly > 0) {
            Text(stringResource(Res.string.editionPreviewWordingOnly, wordingOnly), fontSize = 14.sp, color = OratureColors.NoteText)
        }
        plan.books.filter { it.notable().isNotEmpty() }.forEach { book ->
            Text(
                target.bookTitles[book.projectBookId] ?: book.bookSlug,
                fontWeight = FontWeight.Bold,
                color = OratureColors.RegularText,
                modifier = Modifier.padding(top = 8.dp)
            )
            book.notable().forEach { ChapterLine(it) }
        }
    }
    if (plan.unavailable.isNotEmpty()) {
        Text(
            stringResource(Res.string.editionPreviewUnavailable, localizedList(plan.unavailable)),
            fontSize = 14.sp,
            color = OratureColors.NoteText
        )
    }
}

@Composable
private fun ChapterLine(chapter: ChapterUpgradePlan) {
    Column(modifier = Modifier.padding(start = 8.dp)) {
        Text(stringResource(Res.string.chapterNumber, chapter.sort.toString()), fontWeight = FontWeight.SemiBold, color = OratureColors.RegularText)
        Text(outcomeText(chapter), fontSize = 14.sp, color = OratureColors.RegularText)
        if (chapter.outcome == ChapterOutcome.ADOPTS || chapter.outcome == ChapterOutcome.HELD_BACK) {
            chapter.structuralChanges.forEach { group ->
                Text(stringResource(Res.string.listItemBullet, changeText(group)), fontSize = 13.sp, color = OratureColors.NoteText)
            }
        }
        if (chapter.chunksReset) {
            Text(stringResource(Res.string.editionOutcomeChunksReset), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Done(target: OratureEditionChangeTarget, choice: EditionChoice, plan: ProjectUpgradePlan) {
    Text(stringResource(Res.string.editionDoneMessage, choiceText(choice)), color = OratureColors.RegularText)
    plan.books.filter { it.heldBack.isNotEmpty() }.forEach { book ->
        Text(
            stringResource(
                Res.string.editionHeldBackBook,
                target.bookTitles[book.projectBookId] ?: book.bookSlug,
                localizedList(book.heldBack.map { it.sort.toString() })
            ),
            fontSize = 14.sp,
            color = OratureColors.RegularText
        )
    }
}

@Composable
private fun outcomeText(chapter: ChapterUpgradePlan): String = when (chapter.outcome) {
    ChapterOutcome.TEXT_ONLY -> stringResource(Res.string.editionOutcomeTextOnly, chapter.textChangedVerses.size)
    ChapterOutcome.ADOPTS -> stringResource(Res.string.editionOutcomeAdopts)
    ChapterOutcome.HELD_BACK ->
        if (chapter.diff == null) stringResource(Res.string.editionOutcomeMissing)
        else stringResource(Res.string.editionOutcomeHeldBack)
    ChapterOutcome.ADDED -> stringResource(Res.string.editionOutcomeAdded)
    ChapterOutcome.UNCHANGED -> ""
}

@Composable
private fun changeText(group: VerseGroup): String {
    val from = group.from.ranges()
    val to = group.to.ranges()
    return when (group.change) {
        VerseChange.MERGED -> stringResource(Res.string.editionChangeMerged, from, to)
        VerseChange.SPLIT -> stringResource(Res.string.editionChangeSplit, from, to)
        VerseChange.REGROUPED -> stringResource(Res.string.editionChangeRegrouped, from, to)
        VerseChange.RENUMBERED -> stringResource(Res.string.editionChangeRenumbered, from, to)
        VerseChange.ADDED -> stringResource(Res.string.editionChangeAdded, to)
        VerseChange.REMOVED -> stringResource(Res.string.editionChangeRemoved, from)
        VerseChange.MOVED_OUT -> stringResource(Res.string.editionChangeMovedOut, from, group.movedTo?.chapterSlug?.chapterNumber().orEmpty())
        VerseChange.MOVED_IN -> stringResource(Res.string.editionChangeMovedIn, to, group.movedFrom?.chapterSlug?.chapterNumber().orEmpty())
        VerseChange.IDENTICAL, VerseChange.TEXT_CHANGED -> ""
    }
}

@Composable
private fun List<VerseRange>.ranges() = localizedList(map { it.toString() })

/** Chapter slugs end in the chapter number, e.g. `act_20`. */
private fun String.chapterNumber() = substringAfterLast('_').trimStart('0').ifEmpty { this }

@Composable
private fun choiceText(choice: EditionChoice) = sourceEditionText(choice.edition, choice.distinguishingCode)
