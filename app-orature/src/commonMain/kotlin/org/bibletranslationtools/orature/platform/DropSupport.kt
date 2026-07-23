package org.bibletranslationtools.orature.platform

import androidx.compose.ui.draganddrop.DragAndDropEvent
import io.github.vinceglb.filekit.PlatformFile

/**
 * Extract the first file from a drag-and-drop [event] as a [PlatformFile], or null if the drag has
 * no file (or the platform doesn't support file drops here — Android returns null; use Choose File).
 */
expect fun droppedPlatformFile(event: DragAndDropEvent): PlatformFile?
