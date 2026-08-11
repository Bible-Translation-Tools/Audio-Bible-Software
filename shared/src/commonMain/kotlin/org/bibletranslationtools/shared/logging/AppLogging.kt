package org.bibletranslationtools.shared.logging

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Records a failure that the app layer is about to absorb — swallow into UI state, fall back to a
 * default, carry on.
 *
 * Nearly every `catch`/`onFailure` in the app layer exists to keep the screen alive: it sets an
 * `error` string, resets an `isLoading` flag, returns a default, and moves on. That is right for
 * the user and wrong for whoever has to diagnose it later, because the exception is gone. Call
 * this first, then do the recovery as before.
 *
 * [operation] names what was being attempted, in words that mean something in a log — "compiling
 * the chapter", not "compile". [owner] supplies the logger name; pass the object doing the work.
 *
 * Both apps had their own copy of this, along with [launchLogged] below. The duplication was
 * deliberate — `:shared` carries no `androidx.lifecycle.viewmodel` dependency, so it could not
 * host a `ViewModel` extension — but only the receiver type ever differed. The logic lives here
 * now and each app keeps a two-line `ViewModel` shim, which is what the recorder's copy said to
 * do if a third caller turned up. It did: the plugin store, the plugin registrar and the workbook
 * data store are not ViewModels and were printing to stderr for want of somewhere to log.
 */
fun logFailure(owner: Any, operation: String, error: Throwable) {
    LoggerFactory.getLogger(owner::class.java).error("Failed: $operation", error)
}

/**
 * Records a diagnostic — timing, state traces, anomaly markers — at DEBUG.
 *
 * [message] is a lambda so nothing is built unless DEBUG is actually enabled. That matters: the
 * narration position ticker traced the display clock roughly once a second for the whole of
 * playback, and the home screen timed each load stage, all of it interpolating strings and writing
 * to stderr unconditionally. The instrumentation is worth keeping — the clock trace exists to catch
 * a specific "jumps to the beginning" symptom — but only when someone is looking.
 */
inline fun logDebug(owner: Any, message: () -> String) {
    val logger = LoggerFactory.getLogger(owner::class.java)
    if (logger.isDebugEnabled) logger.debug(message())
}

/**
 * [CoroutineScope.launch] with a [CoroutineExceptionHandler] that logs through slf4j.
 *
 * Roughly half the launch sites in the app layer have no local `try`/`runCatching`, and androidx's
 * `viewModelScope` installs no handler, so an exception escaping one of those reaches the JVM
 * default handler and prints to stderr — which is not where either app's logs go. Routing it here
 * puts a failed coroutine in the same place as every backend error.
 *
 * `CancellationException` is never delivered to a [CoroutineExceptionHandler], so ordinary
 * teardown and job cancellation do not log.
 */
fun CoroutineScope.launchLogged(
    owner: Any,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit
): Job {
    val logger = LoggerFactory.getLogger(owner::class.java)
    val handler = CoroutineExceptionHandler { _, error ->
        logger.error("Unhandled exception in an app-layer coroutine", error)
    }
    return launch(context + handler, block = block)
}
