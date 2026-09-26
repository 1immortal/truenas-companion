package app.truenascompanion.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The saved session tokens of one server.
 *
 * [primary] is used for every non-interactive sign-in. [spare] is never used while the primary works, so it outlives
 * whatever happens to the primary and is the fallback before the user has to type a password + 2FA code again.
 */
data class SessionTokens(val primary: IssuedToken? = null, val spare: IssuedToken? = null) {
    val isEmpty: Boolean get() = primary == null && spare == null
    fun all(): List<IssuedToken> = listOfNotNull(primary, spare).distinctBy { it.token }
}

/** Persistence for [SessionTokens]. [remove] must be a compare-and-remove: only the listed token strings are dropped. */
interface TokenStore {
    suspend fun load(serverId: String): SessionTokens
    suspend fun save(serverId: String, tokens: SessionTokens)
    suspend fun remove(serverId: String, tokens: Set<String>)
}

/** A connection that signed in with a token. [mint] calls `auth.generate_token` on it; [close] drops it on failure. */
class TokenConnection<A>(val api: A, val mint: suspend (ttlSeconds: Long) -> String, val close: () -> Unit)

/** What a failure means for the saved tokens. */
enum class TokenFailure {
    /** TrueNAS explicitly refused the token (`AUTH_ERR` / `false`). Only this discards a token. */
    REJECTED,
    /** No network, timeout, dropped socket, HTTP 5xx from the proxy: keep every token and try again later. */
    TRANSIENT,
    /** Anything else (TLS, certificate, unexpected answer): keep the tokens and surface the error. */
    OTHER,
}

fun Throwable.tokenFailure(): TokenFailure = when (this) {
    is TrueNasException.TokenRejected -> TokenFailure.REJECTED
    is TrueNasException.Unreachable, is TrueNasException.Timeout, is TrueNasException.NotConnected -> TokenFailure.TRANSIENT
    is TrueNasException.Http -> if (code >= 500 || code == 408 || code == 429) TokenFailure.TRANSIENT else TokenFailure.OTHER
    else -> TokenFailure.OTHER
}

/**
 * Single owner of the session-token chain, shared by the UI, the periodic alert check, the instant-alerts service and
 * the keep-alive worker (one instance per process, one lock per server), so they never race to use or rotate a token.
 *
 * Why tokens are rotated on *every* token sign-in: in TrueNAS 25.x, when a connection that signed in with
 * `TOKEN_PLAIN` closes, middleware destroys the token it used (`TokenSessionManagerCredentials.logout()` calls
 * `token_manager.destroy(self.token)`). A token is therefore good for exactly one connection. Tokens generated on that
 * connection inherit the original password + 2FA credentials (not the connection), so they survive it.
 */
class SessionTokenManager(
    private val store: TokenStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lock(serverId: String) = locks.getOrPut(serverId) { Mutex() }

    /** Saves the tokens from an interactive sign-in (replaces the old chain). */
    suspend fun replace(serverId: String, tokens: SessionTokens) = lock(serverId).withLock {
        store.save(serverId, tokens)
    }

    suspend fun clear(serverId: String) = lock(serverId).withLock { store.save(serverId, SessionTokens()) }

    /** True if a non-expired token is saved. */
    suspend fun hasUsable(serverId: String): Boolean = store.load(serverId).all().any { !it.expired() }

    /**
     * True if a background renewal is due: the primary has less than half of [ttlSeconds] left, or there is no spare.
     */
    suspend fun renewalDue(serverId: String, ttlSeconds: Long): Boolean {
        val saved = store.load(serverId)
        val primary = saved.primary?.takeUnless { it.expired() } ?: return saved.spare?.expired() == false
        return saved.spare?.takeUnless { it.expired() } == null || primary.remainingMs() < ttlSeconds * 1000 / 2
    }

    /**
     * Signs in with the saved tokens and rotates them before returning. Returns null when no saved token works (the
     * caller then falls back to the remembered password or asks the user). Network and other non-auth errors are
     * thrown and leave the saved tokens untouched, except a token that was already spent on a connection that failed
     * before it could be rotated (TrueNAS destroys it when that connection closes).
     */
    suspend fun <A> connect(
        serverId: String,
        ttlSeconds: Long,
        login: suspend (token: String) -> TokenConnection<A>,
    ): A? = lock(serverId).withLock {
        val saved = store.load(serverId)
        val dead = LinkedHashSet<String>()
        saved.all().filter { it.expired() }.forEach { dead += it.token }
        var result: A? = null
        var error: Throwable? = null
        try {
            for (candidate in saved.all().filterNot { it.token in dead }) {
                val conn = loginWithRetry(candidate.token, login)
                if (conn == null) { dead += candidate.token; continue }
                // From here on the candidate is spent: the server destroys it when this connection closes.
                dead += candidate.token
                val primary = try {
                    mintWithRetry(conn, ttlSeconds)
                } catch (e: Throwable) {
                    conn.close()
                    if (e is CancellationException) throw e
                    if (e.tokenFailure() == TokenFailure.TRANSIENT) throw e
                    // Signed in but may not mint: the original password + 2FA login expired (TrueNAS caps it at 30
                    // days) or was terminated. The spare shares that login, but trying it costs one round trip.
                    continue
                }
                val oldSpare = saved.spare?.takeUnless { it.token in dead || it.expired() }
                val spare = if (oldSpare == null || oldSpare.remainingMs() < ttlSeconds * 1000 / 2) {
                    runCatching { issued(conn.mint(ttlSeconds), ttlSeconds) }.getOrNull() ?: oldSpare
                } else oldSpare
                store.save(serverId, SessionTokens(primary, spare))
                dead.clear() // save() already replaced everything
                result = conn.api
                break
            }
        } catch (e: Throwable) {
            error = e
        }
        if (dead.isNotEmpty()) withContext(NonCancellable) { store.remove(serverId, dead) }
        error?.let { throw it }
        result
    }

    /** One retry on a fresh connection after an explicit rejection (costs ~1 s, only when a prompt would follow). */
    private suspend fun <A> loginWithRetry(token: String, login: suspend (String) -> TokenConnection<A>): TokenConnection<A>? {
        repeat(2) {
            try {
                return login(token)
            } catch (e: Throwable) {
                if (e is CancellationException || e.tokenFailure() != TokenFailure.REJECTED) throw e
            }
        }
        return null
    }

    private suspend fun <A> mintWithRetry(conn: TokenConnection<A>, ttlSeconds: Long): IssuedToken = try {
        issued(conn.mint(ttlSeconds), ttlSeconds)
    } catch (e: Throwable) {
        if (e is CancellationException || e is TrueNasException.NotConnected) throw e
        issued(conn.mint(ttlSeconds), ttlSeconds)
    }

    private fun issued(token: String, ttlSeconds: Long) = IssuedToken(token, clock() + ttlSeconds * 1000)
    private fun IssuedToken.expired() = clock() >= expiresAt
    private fun IssuedToken.remainingMs() = expiresAt - clock()
}
