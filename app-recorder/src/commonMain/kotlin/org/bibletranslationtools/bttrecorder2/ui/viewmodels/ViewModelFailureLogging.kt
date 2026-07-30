package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Records a failure that a ViewModel is about to absorb into its UI state.
 *
 * Nearly every `catch (e: Exception)` in these ViewModels exists to keep the screen alive — it
 * sets an `error` string or resets `isLoading` and moves on. That is right for the user, but it
 * used to discard the exception: 25 of 27 catch blocks logged nothing. The user got a localized
 * message and the log got silence, so "export failed" or "couldn't load chapters" was
 * undiagnosable after the fact.
 *
 * Call this as the first statement of the catch block, then do the UI-state recovery as before.
 * [operation] should name what was being attempted in words that mean something in a log —
 * "compiling the chapter", not "compile".
 *
 * Deliberate best-effort catches are intentionally left silent: the per-take duration probe in
 * [UnitListViewModel] (cosmetic, runs in a loop), the volume-monitor collector in
 * [RecorderViewModel] (documented, cancelled by cleanup), and the date-format fallback in
 * UnitListScreen.
 *
 * This duplicates the same helper in `:app-orature` rather than living in `:shared`, because
 * `:shared` deliberately carries no `androidx.lifecycle.viewmodel` dependency — each app owns
 * its own UI and ViewModel layer. If a third consumer appears, hoist a `CoroutineScope`-based
 * version into `:shared` instead of adding the lifecycle dependency there.
 */
internal fun ViewModel.logFailure(operation: String, error: Throwable) {
    LoggerFactory.getLogger(this::class.java).error("Failed: $operation", error)
}

/**
 * `viewModelScope.launch` with a [CoroutineExceptionHandler] that logs.
 *
 * Just under half the launch sites in these ViewModels have no local `try`/`runCatching`, and
 * androidx's [viewModelScope] installs no handler, so an exception escaping one of those reaches
 * the JVM default handler and prints to stderr — which is not where this app's logs go. Routing
 * it through slf4j puts a failed ViewModel coroutine in the same place as every backend error.
 *
 * `CancellationException` is never delivered to a [CoroutineExceptionHandler], so ordinary
 * ViewModel teardown and job cancellation do not log.
 */
internal fun ViewModel.launchLogged(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit
): Job {
    val logger = LoggerFactory.getLogger(this::class.java)
    val handler = CoroutineExceptionHandler { _, error ->
        logger.error("Unhandled exception in a ViewModel coroutine", error)
    }
    return viewModelScope.launch(context + handler, block = block)
}
