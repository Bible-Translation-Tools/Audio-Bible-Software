package org.bibletranslationtools.bttrecorder2.ui.platform

import javax.swing.SwingUtilities

actual fun runOnUiThread(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        SwingUtilities.invokeLater(block)
    }
}
