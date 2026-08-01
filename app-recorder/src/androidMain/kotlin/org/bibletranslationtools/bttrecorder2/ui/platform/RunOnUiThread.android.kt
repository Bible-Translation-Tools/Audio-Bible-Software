package org.bibletranslationtools.bttrecorder2.ui.platform

import android.os.Handler
import android.os.Looper

actual fun runOnUiThread(block: () -> Unit) {
    val main = Looper.getMainLooper()
    if (Looper.myLooper() == main) {
        block()
    } else {
        Handler(main).post(block)
    }
}
