package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Importing a project backup whose embedded source is a newer version of one already installed.
 *
 * That import updates the installed source's version in place, which makes the timing of the
 * source lookup matter: a lookup taken before the sources are imported reports the version that was
 * there beforehand. Since a derived project is keyed on its source's version, deriving from that
 * stale value creates a *second* project alongside the one every later derivation resolves to — the
 * same book, twice, one of them empty.
 */
class BackupImportSourceVersionTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    /**
     * A backup of an `aa` Jude project carrying its own copy of the English source at [sourceVersion].
     *
     * Only what the importer requires: the manifest naming the project and its source, the marker
     * that makes this a resumable project, its mode, and the embedded source itself. It holds no
     * takes, since what is under test is how the project is derived.
     */
    private fun backup(env: IntegrationEnvironment, sourceVersion: String): File {
        val source = env.withVersion("en_ulb.zip", sourceVersion, setOf(BOOK))
        val manifest = """
            ---
            dublin_core:
              type: 'book'
              conformsto: '0.2'
              format: 'text/usfm'
              identifier: 'ulb'
              title: 'Unlocked Literal Bible'
              subject: 'Bible'
              description: ''
              language:
                direction: 'ltr'
                identifier: 'aa'
                title: 'Qafar af'
              source:
                -
                  identifier: 'ulb'
                  language: 'en'
                  version: '12'
              rights: 'CC BY-SA 4.0'
              creator: 'OratureInfo.SUITE_NAME'
              contributor: []
              relation: []
              publisher: ''
              issued: '2026-09-09'
              modified: '2026-09-09'
              version: '12'
            projects:
              -
                title: 'Jude'
                identifier: '$BOOK'
                sort: 1
                path: './content'
            checking:
              checking_entity: []
              checking_level: ''
        """.trimIndent()

        val zip = File.createTempFile("backup-$sourceVersion", ".zip").apply { deleteOnExit() }
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun put(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            put("manifest.yaml", manifest.toByteArray())
            // Its presence is what marks the backup as a resumable project.
            put(".apps/orature/selected.txt", ByteArray(0))
            put(".apps/orature/project_mode.json", """{"mode":"DIALECT"}""".toByteArray())
            put(".apps/orature/source/en_ulb.zip", source.readBytes())
        }
        return zip
    }

    private fun judProjects(env: IntegrationEnvironment) =
        env.derivedProjects().filter { it.slug == BOOK }

    @Test
    fun `a backup carrying a newer source derives one project, not two`() {
        val env = IntegrationEnvironment.create().also { this.env = it }
        env.import("en_ulb.zip")

        assertEquals(
            ImportResult.SUCCESS,
            env.importer.import(backup(env, "12-1")).blockingGet()
        )

        assertEquals(
            1,
            judProjects(env).size,
            "one project per book; a stale source version derives a second, empty one"
        )
    }

    @Test
    fun `the derived project takes the source's version as imported`() {
        val env = IntegrationEnvironment.create().also { this.env = it }
        env.import("en_ulb.zip")

        env.importer.import(backup(env, "12-1")).blockingGet()

        // One source row, updated in place, and one derived row that agrees with it.
        assertEquals(
            listOf("ulb/v12-1 source", "ulb/v12-1 derived"),
            env.db.resourceMetadataDao.fetchAll().sortedBy { it.id }.map {
                "${it.identifier}/v${it.version} " + if (it.derivedFromFk == null) "source" else "derived"
            }
        )
    }

    @Test
    fun `a backup whose source matches the installed one also derives one project`() {
        // The case that always worked: with nothing to update, no lookup can go stale.
        val env = IntegrationEnvironment.create().also { this.env = it }
        env.import("en_ulb.zip")

        assertEquals(
            ImportResult.SUCCESS,
            env.importer.import(backup(env, "12")).blockingGet()
        )

        assertEquals(1, judProjects(env).size)
    }

    private companion object {
        /** One chapter, 25 verses. */
        const val BOOK = "jud"
    }
}
