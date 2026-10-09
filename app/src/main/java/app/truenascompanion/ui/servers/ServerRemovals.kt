package app.truenascompanion.ui.servers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 1.8.1: "Remove server" with Undo.
 *
 * [request] only marks the server as pending ([mark]); it disappears from the app at once, but its keys, tokens and
 * settings stay. After the Undo window, [finish] runs (sign the saved sessions out on the NAS, then delete everything).
 * [undo] within the window clears the mark and nothing is lost. The timer runs in the app scope, so leaving the screen
 * doesn't cancel it. If the process dies inside the window, [finishLeftovers] completes the removal on the next start:
 * the user confirmed it in a dialog and never pressed Undo.
 *
 * The mark is written (and awaited) before the timer starts, and it's only cleared by a successful [undo] or by
 * [finish] itself, so a process death at any point leaves either the full server or a pending mark, never half a
 * removal. Each removal finishes at most once per process, even if [finishLeftovers] runs while a timer fires.
 */
class ServerRemovals(
    private val scope: CoroutineScope,
    private val mark: suspend (String) -> Unit,
    private val unmark: suspend (String) -> Unit,
    private val pending: suspend () -> Set<String>,
    private val finish: suspend (String) -> Unit,
) {
    private val lock = Mutex()
    private val timers = mutableMapOf<String, Job>()
    /** Removals whose window has closed and whose [finish] is running. */
    private val finishing = mutableSetOf<String>()

    /** Marks [id] as removed and finishes the removal after [windowMs] unless [undo] is called first. */
    suspend fun request(id: String, windowMs: Long) {
        lock.withLock {
            if (id in finishing) return
            mark(id)
            timers.remove(id)?.cancel()
            timers[id] = scope.launch {
                delay(windowMs)
                val mine = lock.withLock { (timers.remove(id) != null).also { if (it) finishing += id } }
                if (mine) runFinish(id)
            }
        }
    }

    /** Takes the removal back. False if the window already closed (the removal is under way or done). */
    suspend fun undo(id: String): Boolean = lock.withLock {
        val timer = timers.remove(id) ?: return@withLock false
        timer.cancel()
        unmark(id)
        true
    }

    /** Completes removals left over from a previous process (app killed inside the Undo window). */
    suspend fun finishLeftovers() {
        val leftovers = lock.withLock {
            (pending() - timers.keys - finishing).also { finishing += it }
        }
        leftovers.forEach { runFinish(it) }
    }

    /** A failed finish keeps the pending mark, so it's retried on the next start; the server stays hidden meanwhile. */
    private suspend fun runFinish(id: String) {
        try {
            finish(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        } finally {
            lock.withLock { finishing -= id }
        }
    }
}
