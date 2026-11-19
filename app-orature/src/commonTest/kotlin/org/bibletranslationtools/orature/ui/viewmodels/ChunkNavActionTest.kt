package org.bibletranslationtools.orature.ui.viewmodels

import org.bibletranslationtools.orature.ui.translation.ChunkingStep
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the chunk-navigation rules that protect recorded takes from being destroyed.
 *
 * Committing chunk edits runs `ResetChunks.resetChapter`, which DELETES the chapter's takes before
 * re-creating chunks. Orature therefore only ever commits when the user moves FORWARD out of
 * Chunking, and warns first when that commit would destroy existing recordings — so cancelling and
 * undoing the chunk change keeps the Blind Draft takes. The port previously committed on ANY step
 * change out of Chunking with no warning, silently and irrecoverably deleting takes.
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
    fun `forward out of chunking with unsaved edits over existing chunks must be confirmed`() {
        // The reported data-loss case: chunks were moved and the chapter already has recordings.
        laterSteps.forEach { target ->
            assertEquals(
                ChunkNavAction.CONFIRM_DATA_LOSS,
                chunkNavAction(
                    current = ChunkingStep.CHUNKING,
                    target = target,
                    hasUnsavedChunkEdits = true,
                    existingChunkCount = 4
                ),
                "moving to $target should warn before destroying recordings"
            )
        }
    }

    @Test
    fun `first time chunking saves without warning when nothing is recorded yet`() {
        // No chunks exist, so no takes can be lost — chunking for the first time must not nag.
        assertEquals(
            ChunkNavAction.SAVE_THEN_NAVIGATE,
            chunkNavAction(
                current = ChunkingStep.CHUNKING,
                target = ChunkingStep.BLIND_DRAFT,
                hasUnsavedChunkEdits = true,
                existingChunkCount = 0
            )
        )
    }

    @Test
    fun `forward out of chunking after undoing the edits does not warn`() {
        // After undo there are no unsaved edits, so nothing destructive happens (the save handler
        // itself no-ops) and the user must not be warned.
        assertEquals(
            ChunkNavAction.SAVE_THEN_NAVIGATE,
            chunkNavAction(
                current = ChunkingStep.CHUNKING,
                target = ChunkingStep.BLIND_DRAFT,
                hasUnsavedChunkEdits = false,
                existingChunkCount = 4
            )
        )
    }

    @Test
    fun `going backward from chunking never commits chunk edits`() {
        // Regression: leaving Chunking BACKWARD used to run the destructive save too, wiping takes
        // with no warning at all.
        assertEquals(
            ChunkNavAction.NAVIGATE,
            chunkNavAction(
                current = ChunkingStep.CHUNKING,
                target = ChunkingStep.CONSUME_AND_VERBALIZE,
                hasUnsavedChunkEdits = true,
                existingChunkCount = 4
            )
        )
    }

    @Test
    fun `staying on chunking never commits`() {
        assertEquals(
            ChunkNavAction.NAVIGATE,
            chunkNavAction(
                current = ChunkingStep.CHUNKING,
                target = ChunkingStep.CHUNKING,
                hasUnsavedChunkEdits = true,
                existingChunkCount = 4
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
                target = ChunkingStep.BLIND_DRAFT,
                hasUnsavedChunkEdits = true,
                existingChunkCount = 4
            )
        )
        assertEquals(
            ChunkNavAction.NAVIGATE,
            chunkNavAction(
                current = ChunkingStep.CONSUME_AND_VERBALIZE,
                target = ChunkingStep.CHUNKING,
                hasUnsavedChunkEdits = true,
                existingChunkCount = 4
            )
        )
    }
}
