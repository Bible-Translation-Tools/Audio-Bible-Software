package org.bibletranslationtools.otter.common

import io.reactivex.plugins.RxJavaPlugins
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fails a test in which an error reached nobody: RxJava's global handler (a `subscribe()` with no
 * error handler) or a thread's uncaught-exception handler (a coroutine with no handler).
 *
 * Fire-and-forget work fails this way, and a test that only looks at return values passes anyway.
 * Use as `@get:Rule val noStrayErrors = NoStrayErrorsRule()`.
 *
 * A test that pins a stray error on purpose calls [drain] and asserts on what it returns.
 * An error that arrives after the test body has returned is not seen: wait for the work first.
 */
class NoStrayErrorsRule : TestRule {
    private val stray = CopyOnWriteArrayList<Throwable>()

    /** The stray errors so far, removed from the record so they no longer fail the test. */
    fun drain(): List<Throwable> = stray.toList().also { stray.removeAll(it) }

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val previousRx = RxJavaPlugins.getErrorHandler()
            val previousThread = Thread.getDefaultUncaughtExceptionHandler()
            RxJavaPlugins.setErrorHandler { stray += it }
            Thread.setDefaultUncaughtExceptionHandler { _, error -> stray += error }
            try {
                base.evaluate()
            } finally {
                RxJavaPlugins.setErrorHandler(previousRx)
                Thread.setDefaultUncaughtExceptionHandler(previousThread)
            }
            if (stray.isNotEmpty()) {
                throw AssertionError(
                    "${stray.size} error(s) reached a global handler instead of a caller; first: ${stray.first()}",
                    stray.first()
                )
            }
        }
    }
}
