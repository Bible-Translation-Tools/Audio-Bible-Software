package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.versification.StandardVersifications
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Importing a source resource container, end to end: real Koin graph, real SQLite, real RC.
 *
 * The fixture is an older English ULB whose text matches the `eng` versification exactly, so its
 * counts are the same with or without gap-filling. The gap tests edit its USFM to create the cases.
 *
 * Ported from `integrationtest.projects.TestRcImport`, with the JavaFX app's own expected counts
 * against the same committed `en_ulb.zip`.
 *
 * ### What these numbers do and do not prove
 *
 * They are a regression pin on the shape of an import:
 *
 *   META  1189  = chapters in the Protestant canon, one whole-chapter chunk each
 *   TITLE 1255  = 1189 chapter titles + 66 book titles
 *   collections 1256 = 1189 chapters + 66 books + 1 root
 *   TEXT  31104 = every verse the text contains
 *
 * Because this text matches `eng` exactly, they don't show gap-filling at work; the tests under
 * "gaps" do, and SourceStructurePlannerTest covers the rules in isolation.
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
     * Gap-filling needs the bundled standard versifications. Import degrades to the parsed text
     * alone when none can be read, silently by design, so their presence is pinned here: without
     * it, the gap tests below would be the only sign and they would fail confusingly.
     */
    @Test
    fun `the standard versifications are available`() {
        val environment = environment()

        StandardVersifications.all.forEach { code ->
            val versification = environment.versification(code)
            assertNotNull(versification, "versification '$code' was not installed")
            assertEquals(50, versification.getChaptersInBook("gen"), "'$code' reads with lower-case book slugs")
        }
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

    // ── gaps: what a versification may and may not add ───────────────────────────────────

    /**
     * An omitted verse inside a chapter stays recordable. Jude is one chapter of 25 verses; with
     * verse 5 removed from the text, the import still writes 25 rows, 24 of them with text.
     */
    @Test
    fun `fills a verse missing inside a chapter`() {
        val environment = environment()
        val withoutVerse = environment.withVerseRemoved("en_ulb.zip", "66-JUD.usfm", verse = 5)

        environment.import(withoutVerse)

        val jude = environment.verseCounts("jud_1")
        assertEquals(24, jude.withText, "verses the text supplies")
        assertEquals(25, jude.total, "the omitted verse gets an empty row")
    }

    /**
     * Nothing is added after a chapter's last verse. From counts alone an unfinished chapter looks
     * the same as a numbering difference (English ULB folds Acts 19:41 into 19:40), and padding that
     * creates a verse with no text. So Jude cut off after verse 10 imports as 10 verses.
     */
    @Test
    fun `never adds verses after the text's last verse`() {
        val environment = environment()
        val truncated = environment.withBookTruncated("en_ulb.zip", "66-JUD.usfm", keepVerses = 10)

        environment.import(truncated)

        val jude = environment.verseCounts("jud_1")
        assertEquals(10, jude.withText)
        assertEquals(10, jude.total)
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
