package org.bibletranslationtools.recorder2.e2e

import android.util.Log

/**
 * Step logging for Android instrumented e2e. Goes to logcat ([TAG]) and instrumentation
 * stdout so Gradle `connectedDebugAndroidTest` output shows the action trail.
 */
internal object E2eLog {
    const val TAG = "RecorderE2E"

    fun step(message: String) {
        val line = "[$TAG] $message"
        Log.i(TAG, message)
        println(line)
    }
}
