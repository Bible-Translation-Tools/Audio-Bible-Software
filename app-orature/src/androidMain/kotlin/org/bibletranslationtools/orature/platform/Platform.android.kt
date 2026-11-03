package org.bibletranslationtools.orature.platform

import org.bibletranslationtools.orature.BuildConfig

actual fun openUrl(url: String) { /* no-op */ }

actual fun appVersion(): String = BuildConfig.VERSION_NAME
