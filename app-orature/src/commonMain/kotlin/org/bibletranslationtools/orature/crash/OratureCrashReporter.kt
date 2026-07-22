package org.bibletranslationtools.orature.crash

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bibletranslationtools.orature.platform.appVersion
import java.io.PrintWriter
import java.io.StringWriter

/** A captured crash: the throwable (for Sentry), a pre-formatted stack trace, and its message. */
data class OratureCrashInfo(
    val throwable: Throwable,
    val stackTrace: String,
    val message: String?
)

/** Everything an uploader needs to file a bug report. */
data class CrashReport(
    val throwable: Throwable,
    val title: String,
    val environment: List<Pair<String, String>>,
    val stackTrace: String,
    val log: String?
)

/**
 * Global uncaught-exception handler + crash state (JVM: `OtterExceptionHandler`). Install once at
 * startup ([install]); when any thread dies with an uncaught exception, the crash is captured into
 * [crash], which the root composable observes to show
 * [org.bibletranslationtools.orature.ui.screens.OratureCrashScreen]. From there the user can send a
 * bug report ([sendReport] — to every configured uploader, e.g. GitHub + Sentry) and close the app.
 *
 * Best-effort like the JVM original: a crash on a background thread reliably surfaces the screen; a
 * crash on the Compose UI thread may leave the composition unable to render.
 */
object OratureCrashReporter {

    private val _crash = MutableStateFlow<OratureCrashInfo?>(null)
    val crash: StateFlow<OratureCrashInfo?> = _crash.asStateFlow()

    @Volatile
    private var handling = false

    private var uploaders: List<CrashReportUploader> = emptyList()
    private var logProvider: () -> String? = { null }
    private var closeApp: () -> Unit = {}

    /**
     * Register the default uncaught-exception handler and the platform hooks.
     * @param uploaders   send the bug report (empty = reporting unavailable / not configured)
     * @param logProvider returns the current log file contents, attached to the report
     * @param closeApp    quits the application (desktop: exitProcess; android: finish)
     */
    fun install(
        uploaders: List<CrashReportUploader>,
        logProvider: () -> String?,
        closeApp: () -> Unit
    ) {
        this.uploaders = uploaders
        this.logProvider = logProvider
        this.closeApp = closeApp

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            report(error)
            runCatching { previous?.uncaughtException(thread, error) }
        }
    }

    /** Capture a throwable as the current crash (also callable directly for the settings test button). */
    fun report(error: Throwable) {
        if (handling) return
        handling = true
        _crash.value = OratureCrashInfo(
            throwable = error,
            stackTrace = stringFromError(error),
            message = error.message
        )
    }

    /** Whether a bug report can actually be sent (at least one uploader was configured at startup). */
    fun canSendReport(): Boolean = uploaders.isNotEmpty()

    /** Send the current crash to every uploader. Returns true if any accepted it. Off-UI-thread safe. */
    fun sendReport(): Boolean {
        val info = _crash.value ?: return false
        if (uploaders.isEmpty()) return false
        val report = CrashReport(
            throwable = info.throwable,
            title = info.message ?: "crash report",
            environment = crashEnvironment(),
            stackTrace = info.stackTrace,
            log = logProvider()
        )
        // Report to all; success if any succeeds (mirrors JVM: GitHub AND Sentry, best-effort each).
        return uploaders.map { runCatching { it.upload(report) }.getOrDefault(false) }.any { it }
    }

    /** Quit the app (JVM: Platform.exit()). */
    fun close() = closeApp()

    private fun crashEnvironment(): List<Pair<String, String>> = listOf(
        "app version" to appVersion(),
        "os" to (System.getProperty("os.name") ?: ""),
        "os version" to (System.getProperty("os.version") ?: ""),
        "os arch" to (System.getProperty("os.arch") ?: ""),
        "java version" to (System.getProperty("java.version") ?: "")
    )

    private fun stringFromError(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
