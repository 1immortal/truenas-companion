package app.truenascompanion

import app.truenascompanion.data.api.IssuedToken
import app.truenascompanion.data.api.SessionTokenManager
import app.truenascompanion.data.api.SessionTokens
import app.truenascompanion.data.api.TokenConnection
import app.truenascompanion.data.api.TokenFailure
import app.truenascompanion.data.api.TokenStore
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.tokenFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** In-memory [TokenStore] with the same compare-and-remove semantics as SettingsStore. */
class MemoryStore : TokenStore {
    val data: MutableMap<String, SessionTokens> = Collections.synchronizedMap(mutableMapOf())
    val saves = AtomicInteger()
    override suspend fun load(serverId: String) = data[serverId] ?: SessionTokens()
    override suspend fun save(serverId: String, tokens: SessionTokens) { saves.incrementAndGet(); data[serverId] = tokens }
    override suspend fun remove(serverId: String, tokens: Set<String>) = synchronized(data) {
        val cur = data[serverId] ?: return
        data[serverId] = SessionTokens(cur.primary?.takeUnless { it.token in tokens }, cur.spare?.takeUnless { it.token in tokens })
    }
}

/**
 * Token lifecycle against a model of TrueNAS 25.10 middleware (plugins/auth.py + auth.py):
 * - TOKEN_PLAIN login succeeds while the token is known (ttl checked by the manager's clock here);
 * - the token a connection signed in with is destroyed when that connection closes;
 * - generate_token on a token session works while the root (password + 2FA) credential is valid.
 */
class TokenLifecycleTest {
    private class FakeNas {
        val valid: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val next = AtomicInteger()
        val logins = Collections.synchronizedList(mutableListOf<String>())
        val rejectedLogins = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var down = false
        @Volatile var rootExpired = false
        @Volatile var mintFailure: Throwable? = null
        @Volatile var loginDelayMs = 0L

        fun issue(): String = "tok-${next.incrementAndGet()}".also { valid += it }

        suspend fun login(token: String): TokenConnection<String> {
            if (loginDelayMs > 0) delay(loginDelayMs)
            if (down) throw TrueNasException.Unreachable("no route")
            logins += token
            if (token !in valid) { rejectedLogins += token; throw TrueNasException.TokenRejected() }
            return TokenConnection(
                api = "session($token)",
                mint = { _ ->
                    mintFailure?.let { throw it }
                    if (rootExpired) throw TrueNasException.Rpc(13, "EACCES", "Not authenticated")
                    issue()
                },
                close = { valid -= token },
            )
        }

        /** Signs in, then closes like the app does after use (the spent token is destroyed). */
        suspend fun useAndClose(m: SessionTokenManager, id: String = "s", ttl: Long = DAY): String? {
            var closeFn: (() -> Unit)? = null
            val api = m.connect(id, ttl) { t -> login(t).also { c -> closeFn = { c.close() } } }
            closeFn?.invoke()
            return api
        }
    }

    private var now = 1_000_000_000_000L
    private val store = MemoryStore()
    private val nas = FakeNas()
    private val manager = SessionTokenManager(store) { now }

    private fun token(t: String, ttl: Long = DAY) = IssuedToken(t, now + ttl * 1000)
    private fun seed(): SessionTokens = SessionTokens(token(nas.issue()), token(nas.issue())).also { store.data["s"] = it }

    @Test fun rotates_on_every_token_login_and_keeps_fresh_spare() = runBlocking {
        val first = seed()
        var previous = first.primary!!.token
        repeat(10) {
            assertNotNull(nas.useAndClose(manager))
            val cur = store.data["s"]!!
            assertNotEquals(previous, cur.primary!!.token)
            assertFalse("spent token must be gone from the store", previous == cur.spare?.token)
            assertEquals(first.spare!!.token, cur.spare!!.token)
            assertTrue(cur.primary!!.token in nas.valid)
            previous = cur.primary!!.token
        }
        assertTrue(nas.rejectedLogins.isEmpty())
    }

    @Test fun network_error_keeps_every_token() = runBlocking {
        val saved = seed()
        nas.down = true
        try { nas.useAndClose(manager); fail("expected Unreachable") } catch (e: TrueNasException.Unreachable) { /* ok */ }
        assertEquals(saved, store.data["s"])
        nas.down = false
        assertNotNull(nas.useAndClose(manager))
    }

    @Test fun rejected_primary_is_retried_once_then_spare_is_used() = runBlocking {
        val saved = seed()
        nas.valid -= saved.primary!!.token // e.g. revoked
        assertNotNull(nas.useAndClose(manager))
        assertEquals(2, nas.rejectedLogins.count { it == saved.primary!!.token })
        val cur = store.data["s"]!!
        assertTrue(cur.all().none { it.token == saved.primary!!.token || it.token == saved.spare!!.token })
        assertNotNull(cur.spare) // a new spare was minted because the old one was spent
        assertTrue(cur.all().all { it.token in nas.valid })
    }

    @Test fun mint_failure_on_network_drops_only_the_spent_token() = runBlocking {
        val saved = seed()
        nas.mintFailure = TrueNasException.NotConnected()
        try { nas.useAndClose(manager); fail("expected NotConnected") } catch (e: TrueNasException.NotConnected) { /* ok */ }
        assertEquals(SessionTokens(null, saved.spare), store.data["s"])
        nas.mintFailure = null
        assertNotNull(nas.useAndClose(manager)) // the spare carries the session on
        assertNotNull(store.data["s"]!!.primary)
    }

    @Test fun expired_root_credential_needs_a_real_sign_in() = runBlocking {
        seed()
        nas.rootExpired = true // TrueNAS' 30-day cap since the password + 2FA sign-in
        assertNull(nas.useAndClose(manager))
        assertTrue(store.data["s"]!!.isEmpty)
    }

    @Test fun all_rejected_returns_null_and_clears() = runBlocking {
        val saved = seed()
        nas.valid.clear() // NAS rebooted: tokens live in middleware memory
        assertNull(nas.useAndClose(manager))
        assertTrue(store.data["s"]!!.isEmpty)
        assertEquals(4, nas.rejectedLogins.size) // one retry each
        assertTrue(saved.all().all { it.token in nas.rejectedLogins })
    }

    @Test fun expired_tokens_are_dropped_without_network() = runBlocking {
        store.data["s"] = SessionTokens(IssuedToken("old", now - 1), null)
        assertNull(nas.useAndClose(manager))
        assertTrue(nas.logins.isEmpty())
        assertTrue(store.data["s"]!!.isEmpty)
    }

    @Test fun rejection_does_not_wipe_a_token_saved_meanwhile() = runBlocking {
        store.data["s"] = SessionTokens(token("dead"), null)
        // An interactive sign-in finishes while the rejected login is in flight (store written directly).
        val result = manager.connect("s", DAY) { t ->
            store.data["s"] = SessionTokens(token("fresh"), null)
            nas.login(t)
        }
        assertNull(result)
        assertEquals("fresh", store.data["s"]!!.primary!!.token)
    }

    @Test fun old_spare_is_refreshed_past_half_life() = runBlocking {
        val saved = seed()
        now += DAY * 1000 * 6 / 10
        nas.useAndClose(manager)
        val cur = store.data["s"]!!
        assertNotEquals(saved.spare!!.token, cur.spare!!.token)
        assertEquals(now + DAY * 1000, cur.spare!!.expiresAt)
    }

    @Test fun renewal_due_only_past_half_life_or_without_spare() = runBlocking {
        seed()
        assertFalse(manager.renewalDue("s", DAY))
        now += DAY * 1000 / 2 + 1
        assertTrue(manager.renewalDue("s", DAY))
        store.data["s"] = SessionTokens(token("p"), null)
        assertTrue(manager.renewalDue("s", DAY))
        store.data["s"] = SessionTokens()
        assertFalse(manager.renewalDue("s", DAY))
    }

    @Test fun concurrent_foreground_worker_and_service_never_break_the_chain() = runBlocking {
        seed()
        nas.loginDelayMs = 2
        withContext(Dispatchers.Default) {
            (1..60).map { async { nas.useAndClose(manager) } }.awaitAll()
        }.forEach { assertNotNull(it) }
        assertTrue("no login ever used a spent token", nas.rejectedLogins.isEmpty())
        assertTrue(store.data["s"]!!.all().all { it.token in nas.valid })
        assertEquals(60, nas.logins.size)
    }

    @Test fun servers_do_not_block_each_other() = runBlocking {
        val a = SessionTokens(token(nas.issue()), null); val b = SessionTokens(token(nas.issue()), null)
        store.data["a"] = a; store.data["b"] = b
        withContext(Dispatchers.Default) {
            listOf(async { nas.useAndClose(manager, "a") }, async { nas.useAndClose(manager, "b") }).awaitAll()
        }.forEach { assertNotNull(it) }
    }

    @Test fun error_classification() {
        assertEquals(TokenFailure.REJECTED, TrueNasException.TokenRejected().tokenFailure())
        assertEquals(TokenFailure.TRANSIENT, TrueNasException.Unreachable("x").tokenFailure())
        assertEquals(TokenFailure.TRANSIENT, TrueNasException.Timeout().tokenFailure())
        assertEquals(TokenFailure.TRANSIENT, TrueNasException.NotConnected().tokenFailure())
        assertEquals(TokenFailure.TRANSIENT, TrueNasException.Http(502, "Bad gateway").tokenFailure())
        assertEquals(TokenFailure.TRANSIENT, TrueNasException.Http(503, "Unavailable").tokenFailure())
        assertEquals(TokenFailure.OTHER, TrueNasException.Http(404, "nope").tokenFailure())
        assertEquals(TokenFailure.OTHER, TrueNasException.Tls("x", null).tokenFailure())
        assertEquals(TokenFailure.OTHER, TrueNasException.AuthFailed().tokenFailure())
        assertEquals(TokenFailure.OTHER, IllegalStateException().tokenFailure())
    }

    @Test fun proxy_5xx_while_waking_keeps_tokens() = runBlocking {
        val saved = seed()
        try {
            manager.connect<String>("s", DAY) { throw TrueNasException.Http(502, "Bad gateway") }
            fail("expected Http")
        } catch (e: TrueNasException.Http) { /* ok */ }
        assertEquals(saved, store.data["s"])
    }

    private companion object { const val DAY = 86_400L }
}
