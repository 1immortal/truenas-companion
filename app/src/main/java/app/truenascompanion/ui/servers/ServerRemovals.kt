package app.truenascompanion.ui.servers

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

    /** Marks [id] as removed and finishes the removal after [windowMs] unless [undo] is called first. */
    suspend fun request(id: String, windowMs: Long) {
        mark(id)
        lock.withLock {
            timers.remove(id)?.cancel()
            timers[id] = scope.launch {
                delay(windowMs)
                val mine = lock.withLock { timers.remove(id) != null }
                if (mine) finish(id)
            }
        }
    }

    /** Takes the removal back. False if the window already closed (the removal is under way or done). */
    suspend fun undo(id: String): Boolean {
        val stopped = lock.withLock { timers.remove(id)?.also { it.cancel() } != null }
        if (stopped) unmark(id)
        return stopped
    }

    /** Completes removals left over from a previous process (app killed inside the Undo window). */
    suspend fun finishLeftovers() {
        val leftovers = pending() - lock.withLock { timers.keys.toSet() }
        leftovers.forEach { runCatching { finish(it) } }
    }
}
