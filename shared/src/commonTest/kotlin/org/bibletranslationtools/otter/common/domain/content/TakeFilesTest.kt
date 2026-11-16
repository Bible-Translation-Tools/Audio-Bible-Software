package org.bibletranslationtools.otter.common.domain.content

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TakeFilesTest {

    private val dir = File.createTempFile("takes", "").apply { delete(); mkdirs(); deleteOnExit() }
    private fun name(n: Int) = "en_ulb_jud_c01_v01_t$n.wav"

    @Test
    fun `reserves the first number when its file is free, creating it empty`() {
        val reserved = TakeFiles.reserve(dir, 3, ::name)

        assertEquals(3, reserved.number)
        assertEquals(name(3), reserved.file.name)
        assertTrue(reserved.file.exists())
        assertEquals(0, reserved.file.length())
    }

    @Test
    fun `moves past numbers whose files exist, leaving them untouched`() {
        dir.resolve(name(1)).writeText("older layout's take 1")
        dir.resolve(name(2)).writeText("orphan take 2")

        val reserved = TakeFiles.reserve(dir, 1, ::name)

        assertEquals(3, reserved.number)
        assertEquals("older layout's take 1", dir.resolve(name(1)).readText())
        assertEquals("orphan take 2", dir.resolve(name(2)).readText())
    }

    @Test
    fun `two reservations never get the same file`() {
        val first = TakeFiles.reserve(dir, 1, ::name)
        val second = TakeFiles.reserve(dir, 1, ::name)

        assertEquals(listOf(1, 2), listOf(first.number, second.number))
    }

    @Test
    fun `creates the folder when it is missing`() {
        val missing = dir.resolve("c02")

        val reserved = TakeFiles.reserve(missing, 1, ::name)

        assertTrue(reserved.file.exists())
    }

    @Test
    fun `a staging name is never mistaken for a take`() {
        assertFalse(FileNamer.takeFilenamePattern.matcher(TakeFiles.stagingName()).find())
    }
}
