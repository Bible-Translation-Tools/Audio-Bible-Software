package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.bttrecorder2.legacymigration.InstallUlbTemplates.Outcome
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InstallUlbTemplatesTest {

    private val env = LegacyMigrationEnvironment.create()

    @AfterTest
    fun close() = env.close()

    @Test
    fun `installs the templates asked for, and nothing else`() = runBlocking {
        val outcomes = env.installUlbTemplates().execute(setOf(UlbTemplate.CHUNKS))

        val installed = assertIs<Outcome.Installed>(outcomes.getValue(UlbTemplate.CHUNKS))
        assertTrue(installed.newlyInstalled)
        assertEquals(UlbTemplate.CHUNKS.title, installed.edition.title)
        assertEquals("ulb", installed.edition.identifier)
        assertEquals(setOf(UlbTemplate.CHUNKS), outcomes.keys)
        assertEquals(listOf(installed.edition.id), env.sourceEditions().map { it.id })
    }

    @Test
    fun `running again finds the installed editions instead of installing them again`() = runBlocking {
        val install = env.installUlbTemplates()
        val first = install.execute(UlbTemplate.entries.toSet())
        val second = install.execute(UlbTemplate.entries.toSet())

        UlbTemplate.entries.forEach { template ->
            val before = assertIs<Outcome.Installed>(first.getValue(template))
            val after = assertIs<Outcome.Installed>(second.getValue(template))
            assertTrue(before.newlyInstalled, "$template first run")
            assertEquals(false, after.newlyInstalled, "$template second run")
            assertEquals(before.edition.id, after.edition.id, "$template edition")
        }
        assertEquals(2, env.sourceEditions().size)
    }

    @Test
    fun `finds the verse template by its content, not by labels a look-alike shares`() = runBlocking {
        // Same title, version and dates as the verse template; only Jude 1:1 differs, as with an
        // older bundled ULB.
        assertEquals(ImportResult.SUCCESS, env.installAsNewSource(env.lookAlikeOf(UlbTemplate.VERSES)))
        val lookAlike = env.sourceEditions().single()

        val outcome = env.installUlbTemplates().execute(setOf(UlbTemplate.VERSES)).getValue(UlbTemplate.VERSES)

        val installed = assertIs<Outcome.Installed>(outcome)
        assertTrue(installed.newlyInstalled, "the look-alike must not be taken for the template")
        assertNotEquals(lookAlike.id, installed.edition.id)
        assertEquals(lookAlike.title, installed.edition.title, "the labels really are the same")
        assertEquals(2, env.sourceEditions().size)
    }

    @Test
    fun `a template that can't be read is reported, and nothing is installed`() = runBlocking {
        val unreadable = object : IUlbTemplateFiles {
            override fun open(template: UlbTemplate): InputStream = throw IOException("no such asset")
        }

        val outcome = env.installUlbTemplates(unreadable).execute(setOf(UlbTemplate.CHUNKS)).getValue(UlbTemplate.CHUNKS)

        assertIs<Outcome.NotInstalled>(outcome)
        assertTrue(env.sourceEditions().isEmpty())
    }

    @Test
    fun `a template that isn't a resource container is reported, and nothing is installed`() = runBlocking {
        val notAContainer = object : IUlbTemplateFiles {
            override fun open(template: UlbTemplate): InputStream = ByteArrayInputStream("not a zip".toByteArray())
        }

        val outcome = env.installUlbTemplates(notAContainer).execute(setOf(UlbTemplate.VERSES)).getValue(UlbTemplate.VERSES)

        assertIs<Outcome.NotInstalled>(outcome)
        assertTrue(env.sourceEditions().isEmpty())
    }
}
