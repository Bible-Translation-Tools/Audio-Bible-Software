package org.bibletranslationtools.bttrecorder2.legacymigration

/**
 * What to tell the user after a run, once (M10-1): "N chapters from M books moved; K kept in old
 * storage because …". It holds the journal entries the user hasn't been told about; the UI words
 * them, and [MigrationSummaries.markReported] records that it has. The details stay in the journal.
 */
data class MigrationSummary(
    /** Projects whose recordings are now in the new app. */
    val moved: List<JournalEntry>,
    /** Projects left in the old app's storage, with why. */
    val kept: List<JournalEntry>,
    /** Projects not done yet, which a later launch or version tries again. */
    val waiting: List<JournalEntry>
) {
    val chaptersMoved: Int get() = moved.sumOf { it.chaptersMoved }
    val booksMoved: Int get() = moved.map { it.location }.distinct().size
    val isEmpty: Boolean get() = moved.isEmpty() && kept.isEmpty() && waiting.isEmpty()
}

object MigrationSummaries {

    /** The entries of [journal] the user hasn't been told about, or null when there are none. */
    fun pending(journal: JournalData): MigrationSummary? {
        val unreported = journal.projects.values.filter { !it.reported }
        return MigrationSummary(
            moved = unreported.filter { it.imported },
            kept = unreported.filter { it.state == JournalState.KEPT && !it.imported },
            waiting = unreported.filter { !it.state.terminal }
        ).takeIf { !it.isEmpty }
    }

    /** [journal] with [summary]'s entries marked as told, to be written back. */
    fun markReported(journal: JournalData, summary: MigrationSummary): JournalData {
        val told = (summary.moved + summary.kept + summary.waiting).map { it.key }.toSet()
        return journal.copy(projects = journal.projects.mapValues { (key, entry) ->
            if (key in told) entry.copy(reported = true) else entry
        })
    }
}
