package app.truenascompanion

import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.files.FileTransfers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 1.3.0 file transfers against a fake TrueNAS: only `/_download/...` (the URL core.download returned, no extra
 * credentials) and `/_upload` (single-use `Token`), never `/api/v2.0` (which TrueNAS would log as LEGACY_REST).
 */
class FileTransfersTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test fun downloadFetchesOnlyTheReturnedLinkWithProgress() = runBlocking {
        val data = bytes(700_000)
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(data), 8192).setHeader("Content-Type", "application/octet-stream"))
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Long, Long>>()
        val n = FileTransfers(client, base()).download("/_download/42?x=1", out, data.size.toLong()) { d, t -> progress += d to t }
        assertEquals(data.size.toLong(), n)
        assertArrayEquals(data, out.toByteArray())
        assertEquals(data.size.toLong() to data.size.toLong(), progress.last())
        assertTrue(progress.size > 1)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/_download/42?x=1", r.path)
        assertNull(r.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test fun downloadRefusesOtherUrls() = runBlocking {
        val e = runCatching { FileTransfers(client, base()).download("/api/v2.0/filesystem/get", ByteArrayOutputStream(), 1) { _, _ -> } }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test fun uploadPostsMultipartToFileApplicationWithToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"job_id": 9}""").setHeader("Content-Type", "application/json"))
        val data = bytes(300_000)
        var last = 0L
        val id = FileTransfers(client, base()).upload("/mnt/tank/docs/report.pdf", "single-use", "report.pdf", data.size.toLong(), { data.inputStream() }) { d, _ -> last = d }
        assertEquals(9L, id)
        assertEquals(data.size.toLong(), last)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/_upload", r.path)
        assertEquals("Token single-use", r.getHeader("Authorization"))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = r.body.readByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        val dataPart = text.indexOf("name=\"data\"")
        val filePart = text.indexOf("name=\"file\"; filename=\"report.pdf\"")
        assertTrue(dataPart in 0 until filePart) // "data" must be the first part, "file" the second (file_app.py)
        val json = text.substring(text.indexOf('{', dataPart), text.indexOf("\r\n--", dataPart))
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals("\"filesystem.put\"", o["method"].toString())
        assertEquals("""["/mnt/tank/docs/report.pdf",{"append":false,"mode":null}]""", o["params"].toString())
        val start = text.indexOf("\r\n\r\n", filePart) + 4
        assertArrayEquals(data, body.copyOfRange(start, start + data.size))
        assertFalse(r.path!!.contains("/api/"))
    }

    @Test fun proxyLimitsBecomeFriendlyErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(413).setBody("<html>413 Request Entity Too Large</html>"))
        val e = runCatching { FileTransfers(client, base()).upload("/mnt/tank/big.iso", "t", "big.iso", 10, { bytes(10).inputStream() }) { _, _ -> } }.exceptionOrNull()
        assertTrue(e is TrueNasException.Http && e.code == 413)
        server.enqueue(MockResponse().setResponseCode(404))
        val e2 = runCatching { FileTransfers(client, base()).download("/_download/1", ByteArrayOutputStream(), 1) { _, _ -> } }.exceptionOrNull()
        assertTrue(e2 is TrueNasException.Http && e2.code == 404)
    }

    @Test fun cancellingStopsTheDownloadQuickly() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes(5_000_000))).throttleBody(16_384, 100, TimeUnit.MILLISECONDS))
        val out = ByteArrayOutputStream()
        // Own scope: a socket error racing the cancel must not fail the test runner's scope (flaky on CI otherwise).
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob()).async {
            FileTransfers(client, base()).download("/_download/5", out, 5_000_000) { _, _ -> }
        }
        delay(400)
        val t0 = System.currentTimeMillis()
        job.cancel()
        withTimeout(3_000) { runCatching { job.await() } }
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
        assertTrue(job.isCancelled)
        assertTrue(out.size() < 5_000_000)
    }
}
