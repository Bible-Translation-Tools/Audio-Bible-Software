package org.bibletranslationtools.orature.ui.viewmodels

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
 * sets `error = e.message` or resets an `isLoading` flag and moves on. That is the right
 * behaviour for the user, but it used to discard the exception entirely: 26 of 27 catch blocks
 * logged nothing, and the four that said anything used `System.err.println` without a stack
 * trace.
 *
 * The cost was concrete. A missing Koin binding (`OratureProjectDeletion`) threw
 * `NoDefinitionFoundException` inside the project wizard's create path, one of these catch
 * blocks swallowed it, and the only evidence was seven wizard tests timing out after ten
 * seconds with nothing in the log to say why. In production the same silence applies: a user
 * sees a screen that quietly fails to load and the log is empty.
 *
 * Call this as the first statement of the catch block, then do the UI-state recovery as before.
 * [operation] should name what was being attempted, in words that mean something in a log —
 * "loading the narration screen", not "load".
 */
internal fun ViewModel.logFailure(operation: String, error: Throwable) {
    LoggerFactory.getLogger(this::class.java).error("Failed: $operation", error)
}

/**
 * `viewModelScope.launch` with a [CoroutineExceptionHandler] that logs.
 *
 * A third of the launch sites in these ViewModels have no local `try`/`runCatching`, and
 * androidx's [viewModelScope] installs no handler. An exception escaping one of those therefore
 * reaches the JVM's default handler and prints to stderr — which never appears in `orature.log`,
 * the file the Info drawer's "View Logs" opens. Routing it through slf4j instead means a failed
 * ViewModel coroutine is visible in the same place as every other backend error.
 *
 * `CancellationException` is never delivered to a [CoroutineExceptionHandler], so ordinary
 * ViewModel teardown does not log.
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
