package org.bibletranslationtools.otter.common.domain.translation

import kotlin.reflect.KClass
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.IUndoable
import org.bibletranslationtools.otter.common.data.workbook.NOTHING_TO_SAVE
import org.bibletranslationtools.otter.common.data.workbook.inOrder
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementModel

/** [ofType] optionally targets a SPECIFIC marker type (e.g. Book or Chapter) instead of just the
 *  next-in-sequence marker (JVM: `AddOptionalMarkerAction` — the "Add Marker" split-button menu). */
class AddMarkerAction(
    private val markerModel: MarkerPlacementModel,
    private val location: Int,
    private val ofType: KClass<out AudioMarker>? = null
) : IUndoable {
    private var markerId: Int? = null

    override suspend fun execute(): SaveResult<Unit> {
        markerId = markerModel.addMarker(location, ofType)
        return NOTHING_TO_SAVE
    }

    override suspend fun undo(): SaveResult<Unit> {
        markerId?.let {
            markerModel.deleteMarker(it)
        }
        return NOTHING_TO_SAVE
    }

    override suspend fun redo() = execute()
}

class DeleteMarkerAction(
    private val markerModel: MarkerPlacementModel,
    id: Int
) : IUndoable {
    private var markerId: Int = id
    private var location: Int? = null

    override suspend fun execute(): SaveResult<Unit> {
        location = markerModel.markerItems.find { it.id == markerId }?.marker?.location
        markerModel.deleteMarker(markerId)
        return NOTHING_TO_SAVE
    }

    override suspend fun undo(): SaveResult<Unit> {
        location?.let { loc ->
            markerId = markerModel.addMarker(loc)
        }
        return NOTHING_TO_SAVE
    }

    override suspend fun redo() = execute()
}

class MoveMarkerAction(
    private val markerModel: MarkerPlacementModel,
    private val id: Int,
    private val from: Int,
    private val to: Int
) : IUndoable {
    override suspend fun execute(): SaveResult<Unit> {
        markerModel.moveMarker(id, from, to)
        return NOTHING_TO_SAVE
    }

    override suspend fun undo(): SaveResult<Unit> {
        markerModel.moveMarker(id, to, from)
        return NOTHING_TO_SAVE
    }

    override suspend fun redo() = execute()
}

class TakeEditAction(
    private val audio: AssociatedAudio,
    private val newTake: Take,
    private val oldTake: Take,
) : IUndoable {

    lateinit var newMarkerModel: MarkerPlacementModel

    private var undoCallback: suspend () -> Unit = {}
    private var redoCallback: suspend () -> Unit = {}

    override suspend fun execute(): SaveResult<Unit> {
        val checksum = withContext(Dispatchers.IO) { newTake.checksum() }
        return inOrder(
            { audio.setChecking(newTake, TakeCheckingState(CheckingStatus.VERSE, checksum)) },
            { audio.deleteTake(oldTake) }
        )
    }

    override suspend fun undo(): SaveResult<Unit> {
        val result = inOrder(
            { audio.restoreTake(oldTake) },
            { audio.selectTake(oldTake) },
            { audio.deleteTake(newTake) }
        )
        if (result is SaveResult.Saved) undoCallback()
        return result
    }

    override suspend fun redo(): SaveResult<Unit> {
        val result = inOrder(
            { audio.restoreTake(newTake) },
            { audio.selectTake(newTake) },
            { audio.deleteTake(oldTake) }
        )
        if (result is SaveResult.Saved) redoCallback()
        return result
    }

    fun setUndoCallback(op: suspend () -> Unit) { undoCallback = op }
    fun setRedoCallback(op: suspend () -> Unit) { redoCallback = op }
}
