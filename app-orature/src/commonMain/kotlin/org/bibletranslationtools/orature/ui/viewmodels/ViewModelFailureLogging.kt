package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.bibletranslationtools.shared.logging.launchLogged
import org.bibletranslationtools.shared.logging.logDebug
import org.bibletranslationtools.shared.logging.logFailure
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * ViewModel-shaped shims over the `shared.logging` helpers, which hold the actual logic and the
 * reasoning behind it.
 *
 * These stay in the app rather than `:shared` because `:shared` deliberately carries no
 * `androidx.lifecycle.viewmodel` dependency — each app owns its UI and ViewModel layer. Only the
 * receiver is app-specific, so only the receiver lives here.
 */
internal fun ViewModel.logFailure(operation: String, error: Throwable) =
    logFailure(owner = this, operation = operation, error = error)

internal inline fun ViewModel.logDebug(message: () -> String) = logDebug(owner = this, message)

internal fun ViewModel.launchLogged(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit
): Job = viewModelScope.launchLogged(owner = this, context = context, block = block)
