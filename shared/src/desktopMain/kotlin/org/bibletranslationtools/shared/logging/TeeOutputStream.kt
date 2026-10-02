package org.bibletranslationtools.shared.logging

import java.io.OutputStream

/**
 * Writes everything to both [first] and [second]: the console and the log file (see
 * [DesktopFileLogging]). A failure writing to [first] doesn't stop [second] getting it, so a closed
 * console never costs the file its log.
 */
internal class TeeOutputStream(private val first: OutputStream, private val second: OutputStream) : OutputStream() {

    override fun write(b: Int) = both { it.write(b) }

    override fun write(b: ByteArray, off: Int, len: Int) = both { it.write(b, off, len) }

    override fun flush() = both { it.flush() }

    override fun close() = both { it.close() }

    private inline fun both(action: (OutputStream) -> Unit) {
        val firstFailure = runCatching { action(first) }.exceptionOrNull()
        action(second)
        firstFailure?.let { throw it }
    }
}
