package app.truenascompanion.data.api

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 1.7.1 (review P0-3): remembers whether a block of API calls already sent a request that changes something on the NAS.
 * `TrueNasRepository.call` retries a block on a fresh connection after the socket dropped only when nothing but reads
 * went out; otherwise a create/delete/start could run twice.
 */
class RequestTracker : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestTracker> {
        private val READ_SUFFIXES = listOf(
            ".query", ".config", ".get_instance", ".info", ".summary", ".choices", "_choices", ".me", ".status",
            ".versions", ".is_running", ".started", ".get_jobs", ".boot_id", ".host_id", ".product_type",
        )

        /** True for middleware methods that only read (pure, unit tested). Unknown methods count as writes. */
        fun isRead(method: String): Boolean {
            val m = method.lowercase()
            if (m == "core.ping" || m == "core.get_jobs" || m.startsWith("auth.login") || m == "auth.generate_token") return true
            val last = m.substringAfterLast('.')
            return READ_SUFFIXES.any { m.endsWith(it) } || last.startsWith("get_") || last.startsWith("query") || last.startsWith("list") ||
                last.startsWith("check_") || last.endsWith("_info") || last.endsWith("_status")
        }
    }

    @Volatile var writeSent: Boolean = false
        private set

    fun onSend(method: String) { if (!isRead(method)) writeSent = true }
}
