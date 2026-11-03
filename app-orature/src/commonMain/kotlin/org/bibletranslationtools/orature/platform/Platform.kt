package org.bibletranslationtools.orature.platform

/** Open [url] in the default browser (desktop). No-op where unsupported (Android). */
expect fun openUrl(url: String)

/** The app version string shown in the Info drawer (JVM: AppInfo.getVersion). */
expect fun appVersion(): String
