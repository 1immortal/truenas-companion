package app.truenascompanion

import app.truenascompanion.data.api.SharedConnections
import app.truenascompanion.data.api.SharedConnections.Companion.OWNER_APP
import app.truenascompanion.data.api.SharedConnections.Companion.OWNER_SERVICE
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.jobPollDelayMs
import app.truenascompanion.data.model.DashboardLayout
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.WidgetType
import app.truenascompanion.data.repository.TrueNasRepository
import app.truenascompanion.notify.BackgroundConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class BatteryLogicTest {

    private class Alive(var value: Boolean = true)

    /** Minimal TrueNasApi whose only behaviour is `isAlive`. */
    private fun fakeApi(alive: Alive = Alive()): TrueNasApi =
        Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java)) { proxy, m, args ->
            when (m.name) {
                "isAlive" -> alive.value
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "fakeApi"
                else -> null
            }
        } as TrueNasApi

    private val server = ServerConfig(id = "s1", name = "NAS", url = "https://nas.local")

    // --- reconnect backoff ---

    @Test fun reconnectBackoffDoublesAndCaps() {
        val delays = (0L..8L).map { TrueNasRepository.reconnectDelayMs(it) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L, 60_000L), delays)
    }

    @Test fun reconnectBackoffHandlesNegativeAttempt() = assertEquals(2_000L, TrueNasRepository.reconnectDelayMs(-3))

    // --- job polling ---

    @Test fun jobPollingSlowsDownGradually() {
        assertEquals(1_000L, jobPollDelayMs(0))
        assertEquals(1_000L, jobPollDelayMs(4))
        assertEquals(2_000L, jobPollDelayMs(5))
        assertEquals(3_000L, jobPollDelayMs(15))
        assertEquals(5_000L, jobPollDelayMs(30))
        assertEquals(5_000L, jobPollDelayMs(10_000))
    }

    @Test fun twentyMinutePullNeedsFarFewerPolls() {
        var elapsed = 0L; var polls = 0
        while (elapsed < 20 * 60_000L) { elapsed += jobPollDelayMs(polls); polls++ }
        assertTrue("polls=$polls", polls in 200..300) // was 1200 at a fixed 1 s
    }

    // --- token refresh ---

    @Test fun tokenRefreshOnlyPastHalfLife() {
        val day = 86_400L; val now = 1_000_000_000L
        assertFalse(BackgroundConnector.shouldRefreshToken(now + day * 1000, now, day))           // fresh
        assertFalse(BackgroundConnector.shouldRefreshToken(now + day * 1000 / 2 + 1, now, day))   // just over half left
        assertTrue(BackgroundConnector.shouldRefreshToken(now + day * 1000 / 2 - 1, now, day))    // under half left
        assertTrue(BackgroundConnector.shouldRefreshToken(now - 1, now, day))                     // expired
    }

    // --- shared connections ---

    @Test fun borrowPrefersServiceConnection() {
        val shared = SharedConnections()
        val app = fakeApi(); val service = fakeApi()
        shared.publish(server, app, OWNER_APP)
        shared.publish(server, service, OWNER_SERVICE)
        assertSame(service, shared.borrow(server))
        assertSame(app, shared.borrow(server, excludeOwner = OWNER_SERVICE))
        assertNull(shared.borrow(server, excludeOwner = OWNER_APP).takeIf { it !== service })
    }

    @Test fun deadOrWithdrawnConnectionsAreNotLent() {
        val shared = SharedConnections()
        val alive = Alive(); val api = fakeApi(alive)
        shared.publish(server, api, OWNER_SERVICE)
        alive.value = false
        assertNull(shared.borrow(server))
        alive.value = true
        shared.withdraw(server.id, api)
        assertNull(shared.borrow(server))
    }

    @Test fun changedServerSettingsDontReuseOldConnection() {
        val shared = SharedConnections()
        shared.publish(server, fakeApi(), OWNER_SERVICE)
        assertNull(shared.borrow(server.copy(url = "https://other.local")))
        assertNull(shared.borrow(server.copy(id = "s2")))
    }

    @Test fun republishReplacesSameOwner() {
        val shared = SharedConnections()
        val first = fakeApi(); val second = fakeApi()
        shared.publish(server, first, OWNER_APP)
        shared.publish(server, second, OWNER_APP)
        assertSame(second, shared.borrow(server))
        shared.withdraw(server.id, first) // stale withdraw must not remove the newer one
        assertSame(second, shared.borrow(server))
    }

    // --- dashboard live gating ---

    @Test fun liveStatsOnlyWhenALiveCardIsVisible() {
        val all = DashboardLayout.DEFAULT.normalized()
        assertTrue(all.needsLiveStats)
        val noLive = DashboardLayout(all.widgets.map { if (it.type in app.truenascompanion.data.model.LIVE_TYPES) it.copy(visible = false) else it })
        assertFalse(noLive.needsLiveStats)
        val onlyCpu = DashboardLayout(noLive.widgets.map { if (it.type == WidgetType.CPU) it.copy(visible = true) else it })
        assertTrue(onlyCpu.needsLiveStats)
    }
}
