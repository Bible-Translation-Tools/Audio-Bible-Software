package org.bibletranslationtools.orature.ui.viewmodels

import org.bibletranslationtools.orature.ui.translation.ChunkingStep
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the chunk-navigation rules.
 *
 * Committing chunk edits is one undoable rechunk in the chapter's undo history. Orature only ever
 * commits when the user moves FORWARD out of Chunking. The port previously committed on ANY step
 * change out of Chunking.
 */
class ChunkNavActionTest {

    private val laterSteps = listOf(
        ChunkingStep.BLIND_DRAFT,
        ChunkingStep.PEER_EDIT,
        ChunkingStep.KEYWORD_CHECK,
        ChunkingStep.VERSE_CHECK,
        ChunkingStep.FINAL_REVIEW
    )

    @Test
    fun `forward out of chunking saves the chunk edits first`() {
        laterSteps.forEach { target ->
            assertEquals(
                ChunkNavAction.SAVE_THEN_NAVIGATE,
                chunkNavAction(
                    current = ChunkingStep.CHUNKING,
                    target = target
                ),
                "moving to $target should save the chunk edits first"
            )
        }
    }

    @Test
    fun `going backward from chunking never commits chunk edits`() {
        // Regression: leaving Chunking BACKWARD used to run the destructive save too, wiping takes
        // with no warning at all.
        assertEquals(
            ChunkNavAction.NAVIGATE,
            chunkNavAction(
                current = ChunkingStep.CHUNKING,
                target = ChunkingStep.CONSUME_AND_VERBALIZE
            )
        )
    }

    @Test
    fun `staying on chunking never commits`() {
        assertEquals(
            ChunkNavAction.NAVIGATE,
            chunkNavAction(
                current = ChunkingStep.CHUNKING,
                target = ChunkingStep.CHUNKING
            )
        )
    }

    @Test
    fun `navigation between other steps is never destructive`() {
        // Undo state on a later step (e.g. Final Review's marker edits) must not trigger a chunk save.
        assertEquals(
            ChunkNavAction.NAVIGATE,
            chunkNavAction(
                current = ChunkingStep.FINAL_REVIEW,
                target = ChunkingStep.BLIND_DRAFT
            )
        )
        assertEquals(
            ChunkNavAction.NAVIGATE,
            chunkNavAction(
                current = ChunkingStep.CONSUME_AND_VERBALIZE,
                target = ChunkingStep.CHUNKING
            )
        )
    }
}
