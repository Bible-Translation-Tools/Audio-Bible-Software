package org.bibletranslationtools.orature.ui.viewmodels

import org.bibletranslationtools.orature.ui.translation.ChunkingStep
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards when leaving a step commits Chunking's marker edits (JVM: `undock` saves only when
 * `selectedStep.ordinal > CHUNKING.ordinal`).
 *
 * Committing is one undoable rechunk, so it no longer destroys takes and needs no warning; but it
 * must still only happen when the user moves FORWARD out of Chunking. The port once committed on ANY
 * step change out of Chunking (including backward), and a later step's undo state must never
 * trigger a chunk save.
 */
class CommitsChunkEditsTest {

    private val laterSteps = listOf(
        ChunkingStep.BLIND_DRAFT,
        ChunkingStep.PEER_EDIT,
        ChunkingStep.KEYWORD_CHECK,
        ChunkingStep.VERSE_CHECK,
        ChunkingStep.FINAL_REVIEW
    )

    @Test
    fun `forward out of chunking commits the chunk edits`() {
        laterSteps.forEach { target ->
            assertTrue(
                commitsChunkEdits(current = ChunkingStep.CHUNKING, target = target),
                "moving to $target should commit the chunk edits first"
            )
        }
    }

    @Test
    fun `going backward from chunking never commits chunk edits`() {
        // Regression: leaving Chunking BACKWARD used to run the save too.
        assertFalse(
            commitsChunkEdits(
                current = ChunkingStep.CHUNKING,
                target = ChunkingStep.CONSUME_AND_VERBALIZE
            )
        )
    }

    @Test
    fun `staying on chunking never commits`() {
        assertFalse(commitsChunkEdits(current = ChunkingStep.CHUNKING, target = ChunkingStep.CHUNKING))
    }

    @Test
    fun `navigation between other steps never commits`() {
        // Undo state on a later step (e.g. Final Review's marker edits) must not trigger a chunk save.
        ChunkingStep.entries.filter { it != ChunkingStep.CHUNKING }.forEach { current ->
            ChunkingStep.entries.forEach { target ->
                assertFalse(
                    commitsChunkEdits(current = current, target = target),
                    "moving from $current to $target must not commit chunk edits"
                )
            }
        }
    }
}
