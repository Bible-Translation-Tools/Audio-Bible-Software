package org.bibletranslationtools.shared.logging

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeeOutputStreamTest {

    @Test
    fun `both streams get everything`() {
        val console = ByteArrayOutputStream()
        val file = ByteArrayOutputStream()

        TeeOutputStream(console, file).use { it.write("INFO started\n".toByteArray()) }

        assertEquals("INFO started\n", console.toString())
        assertEquals("INFO started\n", file.toString())
    }

    @Test
    fun `a broken console still lets the file have the log`() {
        val brokenConsole = object : OutputStream() {
            override fun write(b: Int) = throw IOException("console closed")
        }
        val file = ByteArrayOutputStream()

        assertFailsWith<IOException> { TeeOutputStream(brokenConsole, file).write("ERROR x\n".toByteArray()) }

        assertEquals("ERROR x\n", file.toString())
    }
}
