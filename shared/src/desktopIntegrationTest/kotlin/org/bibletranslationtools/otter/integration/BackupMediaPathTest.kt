package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.audio.wav.WavFile
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A backup's compiled chapter take is found wherever that backup's manifest says its project lives.
 *
 * The location is part of the file format, and it has changed: backups written while `MEDIA_DIR`
 * held an app resource path declare `./composeResources/files/content`, while those written before
 * and after declare `./content`. Import takes the location from the manifest rather than from the
 * constant, which is what lets both keep working — and is worth pinning, because it is the reason
 * correcting the constant does not strand backups already exported.
 */
class BackupMediaPathTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    /** A one-second WAV, enough to be copied and registered as a take. */
    private fun chapterAudio(): ByteArray {
        val file = File.createTempFile("chapter", ".wav").apply { deleteOnExit() }
        WavFile(file, 1, 44100, 16).let { wav ->
            wav.writer().use { it.write(ByteArray(44100 * 2) { i -> (i % 251).toByte() }) }
        }
        return file.readBytes()
    }

    /**
     * A backup of an `aa` Jude project holding only a compiled chapter take, placed at [mediaDir] and
     * declared there by the manifest.
     */
    private fun backup(env: IntegrationEnvironment, mediaDir: String): File {
        val source = env.withVersion("en_ulb.zip", "12", setOf(BOOK))
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
                path: './$mediaDir'
            checking:
              checking_entity: []
              checking_level: ''
        """.trimIndent()

        val zip = File.createTempFile("backup-media", ".zip").apply { deleteOnExit() }
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun put(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            put("manifest.yaml", manifest.toByteArray())
            put(".apps/orature/project_mode.json", """{"mode":"DIALECT"}""".toByteArray())
            put(".apps/orature/source/en_ulb.zip", source.readBytes())
            put("$mediaDir/c01/$CHAPTER_TAKE", chapterAudio())
            // Marks the backup resumable, and selects the chapter take.
            put(".apps/orature/selected.txt", "c01/$CHAPTER_TAKE\n".toByteArray())
        }
        return zip
    }

    /** The name of the take selected for the chapter, or null when the chapter has none. */
    private fun importedChapterTake(env: IntegrationEnvironment, backup: File): String? {
        assertEquals(ImportResult.SUCCESS, env.importer.import(backup).blockingGet())

        val project = env.derivedProjects().first { it.slug == BOOK }
        val workbook = env.workbook(project)
        return try {
            val chapter = workbook.target.chapters.toList().blockingGet().single()
            chapter.audio.getSelectedTake()?.file?.name
        } finally {
            env.closeWorkbook(workbook)
        }
    }

    @Test
    fun `a chapter take under the standard media directory is imported`() {
        val env = IntegrationEnvironment.create().also { this.env = it }
        env.import("en_ulb.zip")

        assertEquals(CHAPTER_TAKE, importedChapterTake(env, backup(env, "content")))
    }

    @Test
    fun `a chapter take under the path older backups declare is still imported`() {
        // The path older backups declare. Import has to keep reading the location from the
        // manifest rather than from the current constant, or changing the constant makes those
        // backups unreadable.
        val env = IntegrationEnvironment.create().also { this.env = it }
        env.import("en_ulb.zip")

        assertEquals(
            CHAPTER_TAKE,
            importedChapterTake(env, backup(env, "composeResources/files/content"))
        )
    }

    @Test
    fun `the chapter take lands on the chapter's own content row`() {
        val env = IntegrationEnvironment.create().also { this.env = it }
        env.import("en_ulb.zip")
        importedChapterTake(env, backup(env, "content"))

        val project = env.derivedProjects().first { it.slug == BOOK }
        val chapterCollection = env.childrenOf(project).single()
        val metaType = env.db.contentTypeDao.fetchId(ContentType.META)
        val meta = env.db.contentDao.fetchByCollectionId(chapterCollection.id)
            .filter { it.type_fk == metaType }

        assertEquals(1, meta.size, "one chapter-level row")
        assertEquals(
            1,
            env.db.takeDao.fetchByContentId(meta.single().id).size,
            "the compiled chapter take belongs to the chapter, not to a verse"
        )
    }

    private companion object {
        const val BOOK = "jud"
        const val CHAPTER_TAKE = "aa_reg_jud_c01_meta_t1.wav"
    }
}
