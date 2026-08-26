package org.bibletranslationtools.shared.logging

import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Sends the desktop apps' logs to a FILE, because in a packaged app they otherwise go nowhere.
 *
 * The binding is `slf4j-simple`, which writes to stderr. That is fine from a terminal — which is what
 * its comment in `shared/build.gradle.kts` had in mind — but a jpackage app on Windows is launched by a
 * GUI executable with no console attached, so stderr is discarded by the OS. Every `logger.error` in
 * the app, including the ones that record a swallowed initialization failure, was being written to a
 * handle nobody can read. An installed build therefore produced NO diagnostics of any kind, which is
 * how a project could be created with no verses in it and leave nothing behind to explain why.
 *
 * Must be called as the FIRST thing in `main`, before anything touches [LoggerFactory]: slf4j-simple
 * reads its configuration once, when its logger factory initialises, and ignores later changes.
 *
 * Deliberately not logback. A binding swap brings its own packaging risks (config discovery, another
 * module to declare in the runtime image) and the point here is to be able to see the NEXT failure, not
 * to have the best logging setup. Rotation, levels per logger, and async appenders can come later if
 * they are ever wanted.
 */
object DesktopFileLogging {

    /**
     * @param appName the app's data-directory name — the same value its `IDirectoryProvider` is built
     *   with (e.g. "BTT Recorder"), so the log lands beside the database and versification the app is
     *   actually using rather than in a second location that has to be explained separately.
     * @param logFileName the file to write inside that logs directory. Orature passes its established
     *   `orature.log`, which its Info → View Logs screen points at.
     * @param logLevelEnvVar an environment variable that can raise the level, so the `logDebug`
     *   diagnostics can be turned on for one reproduction without shipping a debug build.
     * @return the log file, or null if no writable location could be resolved (in which case logging
     *   stays on stderr exactly as before — never worth failing a launch over).
     */
    fun install(
        appName: String,
        logFileName: String = "$appName.log",
        logLevelEnvVar: String? = null
    ): File? {
        val logFile = runCatching {
            // Reuse the directory provider's own path logic rather than restating the
            // APPDATA/Library/.config rules here; it has no logger of its own, so constructing one
            // this early is safe.
            val logsDirectory = DesktopDirectoryProvider(appName = appName).logsDirectory
            logsDirectory.mkdirs()
            File(logsDirectory, logFileName)
        }.getOrNull() ?: return null

        // Keep one previous run. slf4j-simple's file handling is not documented to append, and the run
        // worth reading is often the one BEFORE the user relaunched to go looking for the log.
        runCatching {
            if (logFile.exists()) {
                logFile.renameTo(File(logFile.parentFile, "${logFile.nameWithoutExtension}.prev.log"))
            }
        }

        // Only set what has not been set already, so a launcher `-D` or an env-specific override still
        // wins — including pointing the logs somewhere else entirely.
        setIfAbsent("org.slf4j.simpleLogger.logFile", logFile.absolutePath)
        setIfAbsent("org.slf4j.simpleLogger.showDateTime", "true")
        setIfAbsent("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS")
        // Left at slf4j-simple's default (info) unless asked otherwise: the `logDebug` traces include a
        // narration position ticker that fires about once a second for the whole of playback.
        logLevelEnvVar?.let { name ->
            System.getenv(name)?.let { setIfAbsent("org.slf4j.simpleLogger.defaultLogLevel", it) }
        }

        // Nothing above catches a crash on a thread with no handler — a Compose or Rx thread dying takes
        // the reason with it, which from the outside looks like the app simply not doing the thing.
        installUncaughtExceptionLogging()

        LoggerFactory.getLogger(DesktopFileLogging::class.java)
            .info("Logging to ${logFile.absolutePath}")
        return logFile
    }

    private fun setIfAbsent(key: String, value: String) {
        if (System.getProperty(key) == null) System.setProperty(key, value)
    }

    private fun installUncaughtExceptionLogging() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                LoggerFactory.getLogger(DesktopFileLogging::class.java)
                    .error("Uncaught exception on thread ${thread.name}", error)
            }
            existing?.uncaughtException(thread, error)
        }
    }
}
