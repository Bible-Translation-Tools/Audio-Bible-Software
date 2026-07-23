package org.bibletranslationtools.orature.platform

import androidx.compose.ui.draganddrop.DragAndDropEvent
import io.github.vinceglb.filekit.PlatformFile

// File drag-and-drop into a dialog isn't a supported flow on Android here; use the Choose File
// picker instead. The dashed drop area still renders for visual parity.
actual fun droppedPlatformFile(event: DragAndDropEvent): PlatformFile? = null
