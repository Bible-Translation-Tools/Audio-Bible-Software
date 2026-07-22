package org.bibletranslationtools.orature.crash

import io.sentry.Attachment
import io.sentry.Sentry
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * Sends crashes to Sentry (JVM: OtterExceptionHandler.sendSentryReport). Tags the event with the
 * crash environment and attaches the log, then captures the throwable so Sentry keeps the real
 * stack frames. Configured from a classpath `sentry.properties` (`dsn`) exactly like the JVM app;
 * absent/blank DSN disables it ([fromClasspath] returns null) — no DSN is ever hardcoded.
 */
class SentryCrashReporter private constructor() : CrashReportUploader {

    private val logger = LoggerFactory.getLogger(SentryCrashReporter::class.java)

    override fun upload(report: CrashReport): Boolean = runCatching {
        Sentry.withScope { scope ->
            report.environment.forEach { (k, v) -> scope.setTag(k.replace(' ', '_'), v) }
            report.log?.takeIf { it.isNotEmpty() }?.let {
                scope.addAttachment(Attachment(it.toByteArray(Charsets.UTF_8), "orature.log"))
            }
            Sentry.captureException(report.throwable)
        }
        true
    }.getOrElse {
        logger.error("Error sending crash report to Sentry.", it)
        false
    }

    companion object {
        /** Build from a classpath `sentry.properties` (`dsn`), initializing Sentry, or null if absent. */
        fun fromClasspath(): SentryCrashReporter? {
            val stream = SentryCrashReporter::class.java.classLoader
                .getResourceAsStream("sentry.properties") ?: return null
            val dsn = Properties().apply { stream.use { load(it) } }
                .getProperty("dsn")?.takeIf { it.isNotBlank() } ?: return null
            Sentry.init { it.dsn = dsn }
            return SentryCrashReporter()
        }
    }
}
