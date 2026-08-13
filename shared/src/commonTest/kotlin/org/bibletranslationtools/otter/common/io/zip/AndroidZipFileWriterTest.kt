package org.bibletranslationtools.otter.common.io.zip

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AndroidZipFileWriter is pure java.util.zip, so it runs on the desktop JVM tier even though it
 * only ships on Android. Its contract is inherited from NioZipFileWriter, which these tests pin
 * down — particularly the preserve-existing-entries behaviour that BackupProjectExporter depends
 * on and that a naive ZipOutputStream implementation silently breaks.
 */
class AndroidZipFileWriterTest {

    private fun tempDir(): File = File.createTempFile("zip-writer-test", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    private fun zipEntries(zip: File): Map<String, ByteArray> =
        java.util.zip.ZipFile(zip).use { archive ->
            archive.entries().asSequence()
                .filter { !it.isDirectory }
                .associate { it.name to archive.getInputStream(it).readBytes() }
        }

    private fun directoryEntries(zip: File): Set<String> =
        java.util.zip.ZipFile(zip).use { archive ->
            archive.entries().asSequence().filter { it.isDirectory }.map { it.name }.toSet()
        }

    @Test
    fun writesStagedEntriesIntoANewArchive() {
        val zip = File(tempDir(), "new.zip")

        AndroidZipFileWriter(zip).use { writer ->
            writer.bufferedWriter(".apps/orature/selected.txt").use { it.write("chapter1.wav") }
            writer.outputStream("media/audio.bin").use { it.write(byteArrayOf(1, 2, 3)) }
        }

        val entries = zipEntries(zip)
        assertEquals("chapter1.wav", entries.getValue(".apps/orature/selected.txt").decodeToString())
        assertContentEquals(byteArrayOf(1, 2, 3), entries.getValue("media/audio.bin"))
    }

    @Test
    fun preservesEntriesAlreadyInTheArchive() {
        // BackupProjectExporter writes the manifest and contributor info BEFORE opening the
        // writer, so anything already in the archive has to survive the repack.
        val zip = File(tempDir(), "existing.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.yaml"))
            out.write("dublin_core: {}".toByteArray())
            out.closeEntry()
        }

        AndroidZipFileWriter(zip).use { writer ->
            writer.bufferedWriter(".apps/orature/chunks.json").use { it.write("[]") }
        }

        val entries = zipEntries(zip)
        assertEquals("dublin_core: {}", entries.getValue("manifest.yaml").decodeToString())
        assertEquals("[]", entries.getValue(".apps/orature/chunks.json").decodeToString())
    }

    @Test
    fun stagedWritesReplaceEntriesOfTheSameName() {
        val zip = File(tempDir(), "replace.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.yaml"))
            out.write("stale".toByteArray())
            out.closeEntry()
        }

        AndroidZipFileWriter(zip).use { writer ->
            writer.bufferedWriter("manifest.yaml").use { it.write("fresh") }
        }

        val entries = zipEntries(zip)
        assertEquals("fresh", entries.getValue("manifest.yaml").decodeToString())
        assertEquals(1, entries.keys.count { it == "manifest.yaml" })
    }

    @Test
    fun copyFileResolvesTheSourceNameAgainstTheDestinationDirectory() {
        // Nio's copyFileTo treats `destination` as a directory, not a full path. Callers such as
        // ProjectFilesAccessor pass RcConstants.SOURCE_DIR and rely on that.
        val work = tempDir()
        val source = File(work, "en_ulb.zip").apply { writeBytes(byteArrayOf(9, 9)) }
        val zip = File(work, "copyfile.zip")

        AndroidZipFileWriter(zip).use { it.copyFile(source, ".apps/orature/source") }

        assertContentEquals(
            byteArrayOf(9, 9),
            zipEntries(zip).getValue(".apps/orature/source/en_ulb.zip")
        )
    }

    @Test
    fun copyDirectoryFlattensRelativePathsAndHonoursTheFilter() {
        val work = tempDir()
        val takes = File(work, "takes").apply { mkdirs() }
        File(takes, "chapter1").mkdirs()
        File(takes, "chapter1/take1.wav").writeBytes(byteArrayOf(1))
        File(takes, "chapter1/take2.mp3").writeBytes(byteArrayOf(2))
        val zip = File(work, "copydir.zip")

        AndroidZipFileWriter(zip).use { writer ->
            writer.copyDirectory(takes, ".apps/orature/takes") { it.endsWith(".wav") }
        }

        val entries = zipEntries(zip)
        assertTrue(entries.containsKey(".apps/orature/takes/chapter1/take1.wav"))
        assertFalse(entries.containsKey(".apps/orature/takes/chapter1/take2.mp3"))
    }

    @Test
    fun emitsDirectoryEntriesSoReadersCanResolveDirectories() {
        // AndroidZipFileReader.exists() probes for a trailing slash, so the parent entries have
        // to be present the way zipfs wrote them.
        val zip = File(tempDir(), "dirs.zip")

        AndroidZipFileWriter(zip).use { writer ->
            writer.bufferedWriter(".apps/orature/selected.txt").use { it.write("x") }
        }

        assertEquals(setOf(".apps/", ".apps/orature/"), directoryEntries(zip))
        AndroidZipFileReader(zip).use { reader ->
            assertTrue(reader.exists(".apps/orature"))
            assertTrue(reader.exists(".apps/orature/selected.txt"))
        }
    }

    @Test
    fun closeIsIdempotent() {
        // SourceProjectExporter closes on the success path and again from doOnError.
        val zip = File(tempDir(), "idempotent.zip")
        val writer = AndroidZipFileWriter(zip)
        writer.bufferedWriter("a.txt").use { it.write("a") }

        writer.close()
        writer.close()

        assertEquals("a", zipEntries(zip).getValue("a.txt").decodeToString())
    }

    @Test
    fun closingWithoutWritesLeavesAnExistingArchiveIntact() {
        val zip = File(tempDir(), "untouched.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.yaml"))
            out.write("keep".toByteArray())
            out.closeEntry()
        }
        val before = zip.readBytes()

        AndroidZipFileWriter(zip).close()

        assertContentEquals(before, zip.readBytes())
    }
}
