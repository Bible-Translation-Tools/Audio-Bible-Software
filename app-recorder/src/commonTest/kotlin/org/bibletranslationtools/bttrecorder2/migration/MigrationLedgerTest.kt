package org.bibletranslationtools.bttrecorder2.migration

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ledger is the only durable record a silent migration leaves, and what makes a run resumable
 * without redoing finished projects or deleting legacy audio twice.
 */
class MigrationLedgerTest {

    private val work = createTempDirectory("migration-ledger").toFile()
    private val file = File(work, "sub/dir/ledger.json")

    @AfterTest
    fun tearDown() {
        work.deleteRecursively()
    }

    private fun entry(
        key: String,
        state: MigrationLedger.State,
        takesCopied: Int = 0
    ) = MigrationLedger.Entry(key = key, state = state, takesCopied = takesCopied)

    @Test
    fun `an entry survives a reload`() {
        MigrationLedger(file).put(
            entry("aaa/ulb/jas", MigrationLedger.State.CLEANED, takesCopied = 7)
        )

        val reloaded = MigrationLedger(file)["aaa/ulb/jas"]

        assertEquals(MigrationLedger.State.CLEANED, reloaded?.state)
        assertEquals(7, reloaded?.takesCopied)
    }

    @Test
    fun `put creates the parent directories`() {
        MigrationLedger(file).put(entry("a/b/c", MigrationLedger.State.MIGRATED))

        assertTrue(file.isFile, "the ledger must be written even on a first run")
    }

    @Test
    fun `only cleaned and skipped count as done`() {
        val ledger = MigrationLedger(file)
        ledger.put(entry("cleaned", MigrationLedger.State.CLEANED))
        ledger.put(entry("skipped", MigrationLedger.State.SKIPPED))
        ledger.put(entry("migrated", MigrationLedger.State.MIGRATED))
        ledger.put(entry("failed", MigrationLedger.State.FAILED))

        assertTrue(ledger.isDone("cleaned"))
        assertTrue(ledger.isDone("skipped"), "no source text is terminal, not retryable")
        // MIGRATED means the takes are in but the legacy audio is still there, so cleanup must run.
        assertFalse(ledger.isDone("migrated"))
        assertFalse(ledger.isDone("failed"))
        assertFalse(ledger.isDone("never-seen"))
    }

    @Test
    fun `unfinished reports exactly the work still outstanding`() {
        val ledger = MigrationLedger(file)
        ledger.put(entry("cleaned", MigrationLedger.State.CLEANED))
        ledger.put(entry("migrated", MigrationLedger.State.MIGRATED))
        ledger.put(entry("failed", MigrationLedger.State.FAILED))

        assertEquals(setOf("migrated", "failed"), ledger.unfinished().map { it.key }.toSet())
    }

    @Test
    fun `an entry is replaced rather than duplicated when its state advances`() {
        val ledger = MigrationLedger(file)
        ledger.put(entry("aaa/ulb/jas", MigrationLedger.State.MIGRATED, takesCopied = 3))
        ledger.put(entry("aaa/ulb/jas", MigrationLedger.State.CLEANED, takesCopied = 3))

        // The MIGRATED-then-CLEANED sequence is what makes a crash between the ledger write and the
        // deletion retry only the deletion.
        assertEquals(1, countEntries(), "one entry per project key, not one per state change")
        assertEquals(MigrationLedger.State.CLEANED, MigrationLedger(file)["aaa/ulb/jas"]?.state)
    }

    @Test
    fun `a corrupt ledger degrades to an empty one instead of blocking migration`() {
        file.parentFile.mkdirs()
        file.writeText("{ this is not json")

        val ledger = MigrationLedger(file)

        assertNull(ledger["anything"])
        assertTrue(ledger.unfinished().isEmpty())
        // And it is still writable afterwards.
        ledger.put(entry("aaa/ulb/jas", MigrationLedger.State.CLEANED))
        assertTrue(MigrationLedger(file).isDone("aaa/ulb/jas"))
    }

    @Test
    fun `a missing ledger reads as empty`() {
        val ledger = MigrationLedger(File(work, "absent.json"))

        assertNull(ledger["aaa/ulb/jas"])
        assertFalse(ledger.isDone("aaa/ulb/jas"))
    }

    @Test
    fun `skipped and failed notes are retained for the log`() {
        MigrationLedger(file).put(
            MigrationLedger.Entry(
                key = "aaa/reg/jas",
                state = MigrationLedger.State.SKIPPED,
                takesSkipped = listOf("c1 v1: missing file"),
                sourceAudio = "aaa_source.tr",
                note = "no source text for 'reg'"
            )
        )

        val reloaded = MigrationLedger(file)["aaa/reg/jas"]!!

        assertEquals(listOf("c1 v1: missing file"), reloaded.takesSkipped)
        assertEquals("aaa_source.tr", reloaded.sourceAudio)
        assertEquals("no source text for 'reg'", reloaded.note)
    }

    /** Entry count as actually persisted, to prove `put` replaces by key. */
    private fun countEntries(): Int =
        Regex("\"key\"").findAll(file.readText()).count()
}
