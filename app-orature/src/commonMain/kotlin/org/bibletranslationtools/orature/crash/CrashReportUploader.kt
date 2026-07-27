package org.bibletranslationtools.orature.crash

import org.slf4j.LoggerFactory
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

/** Uploads a crash as a bug report to some backend (JVM: `GithubReporter` / Sentry). */
interface CrashReportUploader {
    /** @return true if the report was accepted. */
    fun upload(report: CrashReport): Boolean
}

/**
 * Opens a GitHub issue for a crash (port of the JVM `GithubReporter`): POSTs a JSON issue with an
 * environment table, the stack trace, and the log, labelled "crash report". Configured from a
 * classpath `github.properties` (`repo-url`, `oauth-token`) exactly like the JVM app; if that file
 * is absent the reporter is disabled ([fromClasspath] returns null) and no token is ever hardcoded.
 */
class GithubCrashReportUploader(
    private val repositoryUrl: String,
    private val oauthToken: String
) : CrashReportUploader {

    private val logger = LoggerFactory.getLogger(GithubCrashReportUploader::class.java)

    override fun upload(report: CrashReport): Boolean {
        val body = buildString {
            append("\nEnvironment\n======\n")
            append("Environment Key | Value\n:----: | :----:\n")
            report.environment.forEach { (k, v) -> append("$k | $v\n") }
            if (report.stackTrace.isNotEmpty()) {
                append("\nStack trace\n======\n```java\n").append(report.stackTrace).append("\n```\n")
            }
            if (!report.log.isNullOrEmpty()) {
                append("\nLog history\n======\n```java\n").append(report.log).append("\n```\n")
            }
        }
        val payload = JsonIssue(report.title, body, listOf("crash report")).toJson()
        return runCatching { postIssue(payload) }
            .onFailure { logger.error("Error sending crash report to GitHub.", it) }
            .getOrDefault(false)
    }

    private fun postIssue(payload: String): Boolean {
        val connection = (URL(repositoryUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "token $oauthToken")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }
        val code = connection.responseCode
        connection.disconnect()
        val ok = code in 200..299
        if (!ok) logger.error("GitHub issue POST failed with HTTP $code")
        return ok
    }

    companion object {
        /** Build from a classpath `github.properties` (`repo-url` + `oauth-token`), or null if absent. */
        fun fromClasspath(): GithubCrashReportUploader? {
            val stream = GithubCrashReportUploader::class.java.classLoader
                .getResourceAsStream("github.properties") ?: return null
            val props = Properties().apply { stream.use { load(it) } }
            val repo = props.getProperty("repo-url")?.takeIf { it.isNotBlank() } ?: return null
            val token = props.getProperty("oauth-token")?.takeIf { it.isNotBlank() } ?: return null
            return GithubCrashReportUploader(repo, token)
        }
    }
}

/** Minimal JSON builder for the GitHub issue payload (avoids adding a JSON dependency). */
private class JsonIssue(val title: String, val body: String, val labels: List<String>) {
    fun toJson(): String {
        val labelsJson = labels.joinToString(",", "[", "]") { "\"${escape(it)}\"" }
        return "{\"title\":\"${escape(title)}\",\"body\":\"${escape(body)}\",\"labels\":$labelsJson}"
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
