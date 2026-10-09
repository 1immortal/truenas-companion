package app.truenascompanion.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` that lets coroutine cancellation through (1.8.0, code review P1-11). Plain `runCatching` turns a
 * cancelled call into a failure value, so a cancelled screen or worker kept going (and, e.g., the dashboard showed
 * "unavailable" instead of stopping). Use this around suspending calls.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
