package org.bibletranslationtools.orature.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import io.github.vinceglb.filekit.PlatformFile
import java.awt.datatransfer.DataFlavor
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
actual fun droppedPlatformFile(event: DragAndDropEvent): PlatformFile? {
    val transferable = runCatching { event.awtTransferable }.getOrNull() ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    val files = runCatching {
        transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
    }.getOrNull()
    val file = files?.firstOrNull() as? File ?: return null
    return PlatformFile(file)
}
