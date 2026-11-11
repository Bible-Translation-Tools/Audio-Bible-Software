package org.bibletranslationtools.otter.common.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** The dispatchers coroutine code runs on. Injected, so a test can replace them. */
class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main
)

/**
 * A blocking storage call, off the caller's thread: what `fromCallable { }.doOnError { log }
 * .subscribeOn(Schedulers.io())` was. A failure is logged once with [context] and rethrown.
 *
 * [block] is deliberately not suspending: a SQLDelight transaction is bound to its thread, so
 * nothing inside one may suspend.
 */
suspend fun <T> AppDispatchers.storageCall(logger: Logger, context: () -> String, block: () -> T): T =
    withContext(io) {
        try {
            block()
        } catch (e: Exception) {
            logger.error(context(), e)
            throw e
        }
    }

/** [runCatching] for suspending code: cancellation is rethrown, never captured as a failure. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/**
 * An optional step: a failure is logged and the caller carries on with `null`. Marks on purpose
 * what `blockingSubscribe { }`, an ignored `Completable.blockingGet()` and `onErrorReturnItem`
 * did by accident. Cancellation still propagates.
 */
inline fun <T> bestEffort(logger: Logger, what: String, block: () -> T): T? =
    runCatchingCancellable(block)
        .onFailure { logger.error("Continuing after a failed optional step: $what", it) }
        .getOrNull()

/** A background job that failed where nobody was waiting for it. */
data class BackgroundFailure(val what: String, val error: Throwable)

/**
 * The owner of work that outlives a screen, and the one place its failures go: every entry point
 * gets the same behavior, which `RxJavaPlugins.setErrorHandler` gave to only one of four.
 *
 * A failure is logged and published on [failures]; it never cancels other work in the scope.
 */
class AppScope(dispatchers: AppDispatchers) : CoroutineScope {
    private val logger = LoggerFactory.getLogger(AppScope::class.java)

    private val _failures = MutableSharedFlow<BackgroundFailure>(extraBufferCapacity = 64)
    val failures: SharedFlow<BackgroundFailure> = _failures

    override val coroutineContext = SupervisorJob() + dispatchers.default +
        CoroutineExceptionHandler { _, error -> report("an unhandled background job", error) }

    fun report(what: String, error: Throwable) {
        logger.error("Background failure: $what", error)
        _failures.tryEmit(BackgroundFailure(what, error))
    }
}
