package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.bibletranslationtools.shared.logging.launchLogged
import org.bibletranslationtools.shared.logging.logFailure
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * ViewModel-shaped shims over [org.bibletranslationtools.shared.logging.logFailure] and
 * [org.bibletranslationtools.shared.logging.launchLogged], which hold the actual logic and the
 * reasoning behind it.
 *
 * These stay in the app rather than `:shared` because `:shared` deliberately carries no
 * `androidx.lifecycle.viewmodel` dependency — each app owns its UI and ViewModel layer. Only the
 * receiver is app-specific, so only the receiver lives here.
 *
 * Deliberate best-effort catches are intentionally left silent: the per-take duration probe in
 * [UnitListViewModel] (cosmetic, runs in a loop), the volume-monitor collector in
 * [RecorderViewModel] (documented, cancelled by cleanup), and the date-format fallback in
 * UnitListScreen.
 */
internal fun ViewModel.logFailure(operation: String, error: Throwable) =
    logFailure(owner = this, operation = operation, error = error)

internal fun ViewModel.launchLogged(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit
): Job = viewModelScope.launchLogged(owner = this, context = context, block = block)
