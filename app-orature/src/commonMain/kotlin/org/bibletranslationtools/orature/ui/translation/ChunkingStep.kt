package org.bibletranslationtools.orature.ui.translation

import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.blind_draft_and_self_edit
import org.bibletranslationtools.orature.resources.chunking
import org.bibletranslationtools.orature.resources.consume_and_verbalize
import org.bibletranslationtools.orature.resources.final_review
import org.bibletranslationtools.orature.resources.keyword_check
import org.bibletranslationtools.orature.resources.peer_edit
import org.bibletranslationtools.orature.resources.verse_check
import org.jetbrains.compose.resources.StringResource

/**
 * The ordered steps of the oral-translation workflow (JVM: `controls.model.ChunkingStep`). The
 * steps drawer lists them; the page shows the current step's screen. [title] is the localized label.
 */
enum class ChunkingStep(val title: StringResource) {
    CONSUME_AND_VERBALIZE(Res.string.consume_and_verbalize),
    CHUNKING(Res.string.chunking),
    BLIND_DRAFT(Res.string.blind_draft_and_self_edit),
    PEER_EDIT(Res.string.peer_edit),
    KEYWORD_CHECK(Res.string.keyword_check),
    VERSE_CHECK(Res.string.verse_check),
    FINAL_REVIEW(Res.string.final_review)
}
