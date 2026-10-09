package app.truenascompanion

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import app.truenascompanion.data.api.JobMerge
import app.truenascompanion.data.api.JsonRpcClient
import app.truenascompanion.data.api.RequestTracker
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.LogLine
import app.truenascompanion.data.net.PinningTrustManager
import app.truenascompanion.data.repository.CallRetry
import app.truenascompanion.data.security.LockIntegrity
import app.truenascompanion.data.security.LockSettings
import app.truenascompanion.ui.lock.Biometrics
import app.truenascompanion.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext

/** 1.8.0 code, battery and security items (code-perf.md, security.md). */
class V180CodeTest {

    private fun job(id: Long, vararg fields: Pair<String, String>) =
        JsonObject(mapOf("id" to JsonPrimitive(id)) + fields.associate { it.first to JsonPrimitive(it.second) })

    @Test fun jobSnapshotDoesNotOverwriteNewerEvents() {
        // An event for job 7 arrived while core.get_jobs was in flight: its newer state must win.
        val map = mutableMapOf(7L to job(7, "state" to "SUCCESS"))
        JobMerge.mergeSnapshot(map, listOf(job(7, "state" to "RUNNING", "method" to "pool.scrub"), job(8, "state" to "RUNNING")))
        assertEquals("SUCCESS", map[7]!!["state"]!!.jsonPrimitive.content)
        assertEquals("pool.scrub", map[7]!!["method"]!!.jsonPrimitive.content) // fields only the snapshot had are kept
        assertEquals("RUNNING", map[8]!!["state"]!!.jsonPrimitive.content)
    }

    @Test fun jobMapIsTrimmedToNewest() {
        val map = (1L..10L).associateWith { job(it) }.toMutableMap()
        JobMerge.trim(map, 4)
        assertEquals(setOf(7L, 8L, 9L, 10L), map.keys)
        JobMerge.trim(map, 10)
        assertEquals(4, map.size)
    }

    @Test fun logLinesGetIncreasingKeys() {
        val a = LogLine("x", null); val b = LogLine("x", null)
        assertTrue(b.seq > a.seq)
        assertFalse(a == b) // identical text no longer collides as a list key
    }

    @Test fun runCatchingCancellableRethrowsCancellation() {
        assertTrue(runCatchingCancellable { error("boom") }.isFailure)
        assertEquals(3, runCatchingCancellable { 3 }.getOrThrow())
        try {
            runCatchingCancellable { throw CancellationException("gone") }
            fail("cancellation was swallowed")
        } catch (e: CancellationException) { /* expected */ }
    }

    @Test fun strongBiometricsOnAndroid11Plus() {
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, Biometrics.authenticatorsFor(30))
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, Biometrics.authenticatorsFor(36))
        // Android 8–10 can only combine "weak" biometrics with the screen lock.
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, Biometrics.authenticatorsFor(29))
    }

    private class FakeSigner(var key: Boolean) : LockIntegrity.Signer {
        override fun hasKey() = key
        override fun sign(data: String): String? = if (key) "mac:" + data.hashCode() else null
    }

    @Test fun lockSettingsIntegrity() {
        val saved = LockIntegrity.signer
        try {
            val signer = FakeSigner(key = false)
            LockIntegrity.signer = signer
            val json = """{"enabled":true}"""
            assertEquals(LockIntegrity.Verdict.OK, LockIntegrity.verdict(null, null))
            assertEquals(LockIntegrity.Verdict.UNSIGNED, LockIntegrity.verdict(json, null)) // from before 1.8.0
            signer.key = true
            val tag = signer.sign(json)
            assertEquals(LockIntegrity.Verdict.OK, LockIntegrity.verdict(json, tag))
            assertEquals(LockIntegrity.Verdict.TAMPERED, LockIntegrity.verdict("""{"enabled":false}""", tag))
            assertEquals(LockIntegrity.Verdict.TAMPERED, LockIntegrity.verdict(json, null))
            val closed = LockIntegrity.failClosed(LockSettings(enabled = false))
            assertTrue(closed.enabled && closed.confirmDangerous && closed.privacyScreen)
        } finally {
            LockIntegrity.signer = saved
        }
    }

    @Test fun readOnlyCallIsRetriedOnFreshConnection() = runBlocking {
        val connects = AtomicInteger()
        val result = CallRetry.run({ connects.incrementAndGet() }) { conn ->
            currentCoroutineContext()[RequestTracker]?.onSend("pool.query")
            if (conn == 1) throw TrueNasException.NotConnected()
            "ok on $conn"
        }
        assertEquals("ok on 2", result)
    }

    @Test fun writeIsNeverRepeatedAfterDrop() = runBlocking {
        val runs = AtomicInteger()
        try {
            CallRetry.run({ Unit }) {
                runs.incrementAndGet()
                currentCoroutineContext()[RequestTracker]?.onSend("pool.dataset.delete")
                throw TrueNasException.NotConnected()
            }
            fail("expected Interrupted")
        } catch (e: TrueNasException.Interrupted) { /* expected */ }
        assertEquals(1, runs.get())
    }

    @Test fun eventOverflowClosesConnection() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                repeat(1200) { i ->
                    webSocket.send("""{"jsonrpc":"2.0","method":"collection_update","params":{"msg":"changed","collection":"x","id":$i}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = webSocket.close(1000, null).let { }
        }))
        server.start()
        try {
            val client = JsonRpcClient(OkHttpClient(), server.url("/api/current").toString().replace("http", "ws"), PinningTrustManager(null))
            val gate = CompletableDeferred<Unit>()
            val first = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) {
                client.events.collect { first.complete(Unit); gate.await() } // a stuck subscriber
            }
            // Subscribe before the server starts sending.
            kotlinx.coroutines.delay(100)
            client.open()
            withTimeout(10_000) { client.closed.await() }
            assertTrue(client.eventsOverflowed)
            assertFalse(client.isOpen)
            gate.complete(Unit)
            collector.cancel()
        } finally {
            runCatching { server.shutdown() }
        }
    }
}
