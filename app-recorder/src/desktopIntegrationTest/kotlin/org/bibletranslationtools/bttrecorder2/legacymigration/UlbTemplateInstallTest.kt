package org.bibletranslationtools.bttrecorder2.legacymigration

import org.bibletranslationtools.bttrecorder2.legacymigration.LegacyMigrationEnvironment.Companion.bundledUlb
import org.bibletranslationtools.bttrecorder2.legacymigration.LegacyMigrationEnvironment.Companion.legacyChunks
import org.bibletranslationtools.bttrecorder2.legacymigration.LegacyMigrationEnvironment.Companion.templateFile
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The templates installed into a real database next to the bundled ULB, as the migration will
 * install them, and projects created from them.
 *
 * Installing three whole Bibles takes a while, so the class shares one environment.
 */
class UlbTemplateInstallTest {

    companion object {
        private lateinit var env: LegacyMigrationEnvironment
        private lateinit var bundledBefore: LegacyMigrationEnvironment.EditionSnapshot
        private lateinit var installs: Map<UlbTemplate, ImportResult>
        private var bundledId = 0
        private lateinit var templateIds: Map<UlbTemplate, Int>

        @BeforeClass
        @JvmStatic
        fun installTemplates() {
            env = LegacyMigrationEnvironment.create()
            assertEquals(ImportResult.SUCCESS, env.importThroughChain(bundledUlb()), "installing the bundled ULB")
            bundledId = env.sourceEditions().single().id
            bundledBefore = env.snapshot(bundledId)

            installs = UlbTemplate.entries.associateWith { env.installAsNewSource(templateFile(it)) }

            // The verse template may share the bundled ULB's title, so tell them apart by id.
            val templates = env.sourceEditions().filter { it.id != bundledId }
            templateIds = UlbTemplate.entries.associateWith { template ->
                templates.single { it.title == template.title }.id
            }
        }

        @AfterClass
        @JvmStatic
        fun close() = env.close()
    }

    @Test
    fun `each template installs as an edition of its own, next to the bundled ULB`() {
        assertEquals(UlbTemplate.entries.associateWith { ImportResult.SUCCESS }, installs)
        assertEquals(3, env.sourceEditions().size, env.sourceEditions().joinToString { "${it.title} v${it.version}" })
        val bundled = env.sourceEditions().single { it.id == bundledId }
        templateIds.values.map { id -> env.sourceEditions().single { it.id == id } }.forEach { template ->
            assertEquals("ulb", template.identifier, template.title)
            assertEquals(bundled.languageFk, template.languageFk, template.title)
            assertEquals(bundled.creator, template.creator, template.title)
        }
    }

    @Test
    fun `installing the templates leaves the bundled ULB untouched`() {
        assertEquals(bundledBefore, env.snapshot(bundledId))
    }

    @Test
    fun `installing a template that is already installed adds nothing`() {
        val again = env.installAsNewSource(templateFile(UlbTemplate.CHUNKS))
        assertNotEquals(ImportResult.SUCCESS, again)
        assertEquals(3, env.sourceEditions().size)
    }

    @Test
    fun `a project from the chunk template records the old app's chunks`() {
        val book = env.sourceBook("jas", templateIds.getValue(UlbTemplate.CHUNKS))
        val project = env.createDialectProject(book, env.language("aa"))

        (1..5).forEach { chapter ->
            assertEquals(legacyChunks.getValue("jas").getValue(chapter), env.projectVerses(project, chapter), "JAS $chapter")
        }
    }

    @Test
    fun `a verse the old app's chunks skip is still recordable on its own`() {
        // The old definitions for 1CH 4 are …13-16, 17-17, 19-20…: verse 18 is in no chunk.
        val book = env.sourceBook("1ch", templateIds.getValue(UlbTemplate.CHUNKS))
        val project = env.createDialectProject(book, env.language("am"))

        val verses = env.projectVerses(project, 4)
        assertTrue(17..17 in verses && 18..18 in verses && 19..20 in verses, "1CH 4 is $verses")
    }

    @Test
    fun `a project from the verse template records one verse at a time`() {
        val book = env.sourceBook("jas", templateIds.getValue(UlbTemplate.VERSES))
        val project = env.createDialectProject(book, env.language("fr"))

        val verses = env.projectVerses(project, 1)
        assertTrue(verses.all { it.first == it.last }, "JAS 1 is $verses")
        assertEquals(legacyChunks.getValue("jas").getValue(1).flatMap { it.toList() }, verses.map { it.first })
    }
}
