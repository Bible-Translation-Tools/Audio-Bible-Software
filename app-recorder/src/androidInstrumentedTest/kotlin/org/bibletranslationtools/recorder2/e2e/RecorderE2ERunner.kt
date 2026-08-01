package org.bibletranslationtools.recorder2.e2e

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Swaps in [RecorderTestApplication] so instrumented e2e runs with mock audio and primed init
 * instead of the production [org.bibletranslationtools.recorder2.Application].
 */
class RecorderE2ERunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        return super.newApplication(cl, RecorderTestApplication::class.java.name, context)
    }
}
