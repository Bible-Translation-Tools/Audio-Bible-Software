package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.bttrecorder2.legacymigration.InstallUlbTemplates.Outcome
import org.bibletranslationtools.bttrecorder2.legacymigration.LegacyMigrationEnvironment.Companion.legacyChunks
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The templates' chunks as read back from their installed editions, and the planner run on them. */
class LoadTemplateChunksTest {

    companion object {
        private lateinit var env: LegacyMigrationEnvironment
        private lateinit var chunks: TemplateChunks

        @BeforeClass
        @JvmStatic
        fun installAndLoad() = runBlocking {
            env = LegacyMigrationEnvironment.create()
            val editions = env.installUlbTemplates().execute(UlbTemplate.entries.toSet())
                .mapValues { (_, outcome) -> assertIs<Outcome.Installed>(outcome).edition.id }
            chunks = LoadTemplateChunks(env.koin.get()).execute(editions, setOf("jas", "1ch", "jhn"))
        }

        @AfterClass
        @JvmStatic
        fun close() = env.close()
    }

    @Test
    fun `the chunk template's installed edition has the old app's chunks`() {
        listOf("jas" to 1, "jas" to 5, "jhn" to 9).forEach { (book, chapter) ->
            assertEquals(legacyChunks.getValue(book).getValue(chapter), chunks.chapter(UlbTemplate.CHUNKS, book, chapter), "$book $chapter")
        }
    }

    @Test
    fun `a verse the old chunks skip is a unit of its own in the installed chunk template`() {
        val chapter = chunks.chapter(UlbTemplate.CHUNKS, "1ch", 4)!!
        assertTrue(17..17 in chapter && 18..18 in chapter && 19..20 in chapter, "1CH 4 is $chapter")
    }

    @Test
    fun `the verse template's installed edition has one verse per unit`() {
        val chapter = chunks.chapter(UlbTemplate.VERSES, "jas", 1)!!
        assertEquals((1..27).map { it..it }, chapter)
    }

    @Test
    fun `only the books asked for are read`() {
        assertTrue(chunks.hasBook(UlbTemplate.CHUNKS, "jas"))
        assertFalse(chunks.hasBook(UlbTemplate.CHUNKS, "gen"))
        assertNull(chunks.chapter(UlbTemplate.CHUNKS, "jas", 6), "James has five chapters")
    }

    @Test
    fun `a chunked legacy project plans cleanly against the installed chunk template`() {
        var id = 0
        fun take(verses: IntRange) = LegacyTake(++id, 1, 0, null, File("t$id.wav"), true, verses, null)
        val chapter = LegacyChapter(
            1,
            legacyChunks.getValue("jas").getValue(1).map { verses ->
                val take = take(verses)
                LegacyChunk(++id, verses.first, take.id, listOf(take))
            }
        )
        val legacy = LegacyProject(
            id = 1, anthology = "nt", book = LegacyBook("jas", 59), targetLanguage = "aa", sourceLanguage = "en",
            versionLabel = "reg", chunking = LegacyChunking.CHUNKS, contributors = null,
            folder = File("translations/aa/reg/jas"), chapters = listOf(chapter)
        )

        val plan = PlanLegacyMigration().execute(listOf(legacy), chunks)

        assertEquals(emptyList(), plan.legacyProjects.single().problems)
        assertEquals(
            legacyChunks.getValue("jas").getValue(1),
            plan.projects.single().chapters.single().selected.pieces.map { it.verses }
        )
    }
}
