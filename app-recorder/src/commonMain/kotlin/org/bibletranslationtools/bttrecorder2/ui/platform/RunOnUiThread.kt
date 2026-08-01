package org.bibletranslationtools.bttrecorder2.ui.platform

/**
 * Runs [block] on the platform UI thread (Android main looper / Swing EDT).
 * Needed when navigating after Rx/async work that may resume under Compose UI-test
 * dispatchers that are not the real UI thread.
 */
expect fun runOnUiThread(block: () -> Unit)
