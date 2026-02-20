package org.bibletranslationtools.bttrecorder2.ui.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaveEditSessionTest {

    @Test
    fun cutAbsoluteMergesAndMapsFrameSpaces() {
        val session = WaveEditSession(originalTotalFrames = 100)

        assertTrue(session.cutAbsolute(10, 20))
        assertTrue(session.cutAbsolute(18, 30))

        assertEquals(1, session.rangesSnapshot().size)
        assertEquals(10, session.rangesSnapshot().first().start)
        assertEquals(30, session.rangesSnapshot().first().endExclusive)
        assertEquals(80, session.editedTotalFrames)

        assertEquals(35, session.absoluteToRelative(55))
        assertEquals(55, session.relativeToAbsolute(35))
        assertTrue(session.isFrameRemoved(15))
        assertFalse(session.isFrameRemoved(35))
    }

    @Test
    fun undoRedoRestoresCutHistory() {
        val session = WaveEditSession(originalTotalFrames = 50)
        session.cutAbsolute(5, 10)
        session.cutAbsolute(20, 30)

        assertTrue(session.hasEdits())
        assertTrue(session.canUndo())
        assertEquals(35, session.editedTotalFrames)

        assertTrue(session.undo())
        assertEquals(45, session.editedTotalFrames)
        assertEquals(1, session.rangesSnapshot().size)

        assertTrue(session.undo())
        assertFalse(session.hasEdits())
        assertEquals(50, session.editedTotalFrames)

        assertTrue(session.redo())
        assertTrue(session.redo())
        assertEquals(35, session.editedTotalFrames)
        assertEquals(2, session.rangesSnapshot().size)
    }
}
