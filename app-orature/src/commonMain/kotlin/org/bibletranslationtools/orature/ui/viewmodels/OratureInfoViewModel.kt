package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import org.bibletranslationtools.orature.platform.appVersion
import org.bibletranslationtools.orature.platform.canOpenInFileManager
import org.bibletranslationtools.orature.platform.openInFileManager
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Backs the Info drawer (JVM: AppInfoViewModel): exposes the app version and opens the log directory
 * in the OS file manager (JVM: browseApplicationLog). "View Logs" is desktop-only.
 */
class OratureInfoViewModel : ViewModel(), KoinComponent {

    private val directoryProvider: IAppDirectories by inject()

    /** The current app version (JVM: AppInfo.getVersion). */
    val version: String = appVersion()

    /** Whether the logs folder can be revealed (desktop only). */
    val canViewLogs: Boolean = canOpenInFileManager()

    /** Open the application logs directory in the OS file manager (JVM: browseApplicationLog). */
    fun browseLogs() {
        openInFileManager(directoryProvider.logsDirectory)
    }
}
