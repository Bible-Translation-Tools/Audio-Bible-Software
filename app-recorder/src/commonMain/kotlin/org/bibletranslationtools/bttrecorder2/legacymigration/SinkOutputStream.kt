package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import java.io.OutputStream

/** A kotlinx-io sink as an [OutputStream], for the archive writer; buffered, and closes the sink. */
internal class SinkOutputStream(private val sink: RawSink) : OutputStream() {
    private val buffer = Buffer()

    override fun write(b: Int) {
        buffer.writeByte(b.toByte())
        emitIfFull()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        buffer.write(b, off, off + len)
        emitIfFull()
    }

    override fun flush() {
        if (buffer.size > 0) sink.write(buffer, buffer.size)
        sink.flush()
    }

    override fun close() {
        flush()
        sink.close()
    }

    private fun emitIfFull() {
        if (buffer.size >= CHUNK) sink.write(buffer, buffer.size)
    }

    private companion object {
        const val CHUNK = 64L * 1024
    }
}
