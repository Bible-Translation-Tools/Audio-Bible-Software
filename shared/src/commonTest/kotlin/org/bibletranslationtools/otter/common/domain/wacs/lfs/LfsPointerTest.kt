/*
 * Copyright (C) 2020-2026 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.domain.wacs.lfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LfsPointerTest {

    // The real pointer for ingredients/MRK/01.wav on the WACS prototype (verified in Spike B).
    private val oid = "639dad0ac2923f5fe9e9ccfb99aa9b3084048e2e53317d1903e6e899a4f6296a"
    private val pointerText =
        "version https://git-lfs.github.com/spec/v1\n" +
            "oid sha256:$oid\n" +
            "size 9644\n"

    @Test
    fun `parses a well-formed pointer`() {
        val p = LfsPointer.parse(pointerText)
        assertEquals(LfsPointer(oid, 9644L), p)
    }

    @Test
    fun `round-trips serialize to parse`() {
        val original = LfsPointer(oid, 9644L)
        assertEquals(original, LfsPointer.parse(original.serialize()))
    }

    @Test
    fun `serialize matches canonical byte layout`() {
        assertEquals(pointerText, LfsPointer(oid, 9644L).serialize())
    }

    @Test
    fun `isPointer true for pointer bytes, false for audio bytes`() {
        assertTrue(LfsPointer.isPointer(pointerText.encodeToByteArray()))
        // A RIFF/WAVE header (real content) is not a pointer.
        assertFalse(LfsPointer.isPointer(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())))
        assertFalse(LfsPointer.isPointer(ByteArray(0)))
    }

    @Test
    fun `isPointer false for oversized blob even if it starts with the version line`() {
        val big = (LfsPointer.VERSION_LINE + "\n").encodeToByteArray() + ByteArray(4096)
        assertFalse(LfsPointer.isPointer(big))
    }

    @Test
    fun `parse returns null for non-pointer and malformed input`() {
        assertNull(LfsPointer.parse("not a pointer"))
        assertNull(LfsPointer.parse("version https://git-lfs.github.com/spec/v1\noid sha256:tooshort\nsize 1\n"))
        assertNull(LfsPointer.parse("version https://git-lfs.github.com/spec/v1\noid sha256:$oid\n")) // no size
    }
}
