package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.data.primitives.ContentType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Importing a source resource container, end to end: real Koin graph, real SQLite, real RC.
 *
 * Ported from `integrationtest.projects.TestRcImport`, with the JavaFX app's own expected counts
 * against the same committed `en_ulb.zip`.
 *
 * ### What these numbers do and do not prove
 *
 * They are a regression pin on the shape of an import. They are NOT evidence that the versification
 * pre-allocation runs, and it is worth being explicit about that, because the arithmetic invites the
 * opposite conclusion:
 *
 *   META  1189  = chapters in the Protestant canon, one whole-chapter chunk each
 *   TITLE 1255  = 1189 chapter titles + 66 book titles
 *   collections 1256 = 1189 chapters + 66 books + 1 root
 *   TEXT  31104 = every verse the versification declares
 *
 * Those look like versification totals, and they are — but the ULB's text covers every verse the
 * `ufw` versification declares, so a text-only import of this fixture produces byte-identical
 * counts. Measured: disabling pre-allocation entirely changes none of the numbers below, nor the 16
 * blank-text verses, nor the zero uncovered ones. The two paths only diverge for a source whose text
 * omits verses its versification declares, and this fixture is not one.
 *
 * So pre-allocation is covered here in one direction only — [versification is available] pins that
 * the path is reachable, and PlanImportTest pins that a reachable tree is the one used. Proving the
 * divergence needs an incomplete-source fixture, which this repo does not have yet.
 */
class RcImportTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun environment(): IntegrationEnvironment =
        IntegrationEnvironment.create().also { env = it }

    @Test
    fun `importing the ULB writes the expected structure`() {
        environment()
            .import("en_ulb.zip")
            .assertRowCounts(
                RowCount(
                    contents = mapOf(
                        ContentType.TEXT to 31104,
                        ContentType.META to 1189,
                        ContentType.TITLE to 1255
                    ),
                    collections = 1256,
                    links = 1255
                )
            )
    }

    /**
     * Spot-check chapters by verse count, which catches an off-by-one in the text that a total hides.
     */
    @Test
    fun `chapters carry the verse counts the source text provides`() {
        environment()
            .import("en_ulb.zip")
            .assertChapters(
                "ulb",
                ChapterVerse("gen_1", 31),
                ChapterVerse("gen_2", 25),
                ChapterVerse("psa_117", 2),
                ChapterVerse("rev_22", 21)
            )
    }

    /**
     * The precondition the importer hides.
     *
     * `NewSourceImporter` wraps the tree build in a `runCatching` so an unreadable versification
     * degrades to a text-only import rather than failing it — correct for the app, and invisible.
     * This environment initially reproduced that exactly: `getVersification` threw because no
     * versification rows existed, the importer swallowed it, and every assertion above still passed.
     * Pinning reachability separately is what stops a silently inert feature reading as a working one.
     *
     * `ufw` rather than `ulb`: the manifest names the versification, and the ULB names `ufw`.
     * `InitializeVersification` writes both files from the same bundled resource.
     */
    @Test
    fun `versification is available`() {
        val trees = environment().versificationTreesFor("en_ulb.zip")

        assertNotNull(trees, "the versification tree could not be built — the importer would silently fall back")
        assertEquals(66, trees.size, "one tree per book of the canon")
    }

    /**
     * The ULB covers its versification completely, so nothing is pre-allocated-and-left-empty. Pinned
     * as an equality rather than dropped: it is the reason this fixture alone cannot demonstrate
     * pre-allocation, and if it ever becomes non-zero that reason has changed.
     */
    @Test
    fun `the ULB text covers every verse its versification declares`() {
        val uncovered = environment().import("en_ulb.zip").uncoveredVerseRows()

        assertTrue(
            uncovered.isEmpty(),
            "expected no uncovered verses for a complete ULB, found ${uncovered.size}"
        )
    }

    // ── the divergence: a source whose text is incomplete ────────────────────────────────

    /**
     * The point of the whole feature, and the only test here that fails if pre-allocation is off.
     *
     * Jude is one chapter of 25 verses. Truncated to 10, the versification still declares 25, so a
     * pre-allocating import must produce 25 verse rows with 10 of them carrying text — those 15 empty
     * rows are what a translator records into. A text-only import produces 10 rows and no way to
     * record verses 11-25 at all.
     *
     * Every other assertion in this file is satisfied by both paths; this is the discriminator.
     */
    @Test
    fun `pre-allocates the verses a truncated source omits`() {
        val environment = environment()
        val truncated = environment.withBookTruncated("en_ulb.zip", "66-JUD.usfm", keepVerses = 10)

        environment.import(truncated)

        val jude = environment.verseCounts("jud_1")
        assertEquals(10, jude.withText, "verses the truncated text supplies")
        assertEquals(25, jude.total, "verses the versification declares — the rest are pre-allocated")
    }

    /** Truncating one book must not disturb the others: the gap is Jude's, not the import's. */
    @Test
    fun `truncating one book leaves the rest of the canon intact`() {
        val environment = environment()
        val truncated = environment.withBookTruncated("en_ulb.zip", "66-JUD.usfm", keepVerses = 10)

        environment.import(truncated)

        val genesis = environment.verseCounts("gen_1")
        assertEquals(31, genesis.withText)
        assertEquals(31, genesis.total)
    }
}
