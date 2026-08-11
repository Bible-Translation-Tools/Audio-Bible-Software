package org.bibletranslationtools.orature.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import java.io.File

/**
 * A single verse of source text for the marker editor's left panel (JVM: `SourceTextFragment`
 * rows). [index] is the position among the *highlightable* (verse) markers so the panel can
 * highlight the row matching the editor's currently-highlighted marker.
 */
data class OratureVerseText(
    val index: Int,
    val label: String,
    val text: String
)

/**
 * App-scoped handoff for the built-in Verse Marker editor — the in-process analog of the JVM
 * marker *plugin*'s `ParameterizedScope` (which received `wav`, `marker_labels`, `book_slug`,
 * `chapter_number`, `action_title`, `content_title`). Instead of launching a separate process,
 * a host (narration, later translation) compiles the chapter take, fills a [Request] here, and
 * navigates to the marker route; [OratureVerseMarkerViewModel] reads the request on open.
 *
 * We build the editor IN rather than as a plugin because the JVM in-window JavaFX plugin isn't
 * portable and the whole marker engine (`MarkerPlacementModel`) already lives in `:shared`.
 */
class OratureVerseMarkerEditor {

    /**
     * @param takeFile the compiled chapter take whose cues are edited (and written back).
     * @param reservedMarkers the full placeable verse+title marker set (JVM: verse labels +
     *   book/chapter title markers) — narration passes `narration.totalVerses`.
     * @param actionTitle header title (e.g. "Add Verse Markers").
     * @param contentTitle header subtitle (book + chapter).
     * @param sourceText left-panel verse text, indexed by highlightable-marker position.
     * @param onSaved host reload after markers are written back to [takeFile]
     *   (JVM: `onChapterReturnFromPlugin` → `loadFromSelectedChapterFile`).
     */
    class Request(
        val takeFile: File,
        val reservedMarkers: List<AudioMarker>,
        val actionTitle: String,
        val contentTitle: String,
        val sourceText: List<OratureVerseText>,
        val onSaved: suspend () -> Unit
    )

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    /** Populate the pending request; the caller then navigates to the marker route. */
    fun open(request: Request) {
        _request.value = request
    }

    /** Clear the request once the editor has consumed it / closed. */
    fun close() {
        _request.value = null
    }
}
