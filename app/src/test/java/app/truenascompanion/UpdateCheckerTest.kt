package app.truenascompanion

import app.truenascompanion.data.update.UpdateChecker
import app.truenascompanion.data.update.UpdateResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
    private val sha = "a".repeat(64)
    private fun release(tag: String = "v0.5.1", digest: String? = "sha256:$sha", extraAsset: String = "") = """
        {"tag_name":"$tag","name":"TrueNAS Companion $tag","html_url":"https://github.com/1immortal/truenas-companion/releases/tag/$tag",
         "body":"## What's new\n- **Protection** tab\n- Fixed `crash`\n\nSee [docs](https://example.com).",
         "assets":[$extraAsset{"name":"truenas-companion-$tag-debug.apk","size":12345678,
           "browser_download_url":"https://github.com/x/y/releases/download/$tag/app.apk"${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}]}
    """.trimIndent()

    @Test fun parsesDigest() {
        val r = UpdateChecker.parseRelease(release())!!
        assertEquals("0.5.1", r.version); assertEquals(sha, r.sha256); assertEquals(12_345_678L, r.apkSize); assertNull(r.checksumUrl)
    }

    @Test fun fallsBackToChecksumAsset() {
        val r = UpdateChecker.parseRelease(release(digest = null,
            extraAsset = """{"name":"truenas-companion-v0.5.1-debug.apk.sha256","browser_download_url":"https://x/sum"},"""))!!
        assertNull(r.sha256); assertEquals("https://x/sum", r.checksumUrl)
        assertNull(UpdateChecker.parseRelease(release(digest = "md5:abc"))!!.sha256)
    }

    @Test fun noApkMeansNoRelease() {
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"v1.0.0","assets":[{"name":"notes.txt","browser_download_url":"x"}]}"""))
        assertNull(UpdateChecker.parseRelease("not json"))
    }

    @Test fun versions() {
        assertTrue(UpdateChecker.compareVersions("v0.10.0", "0.9.3") > 0)
        assertTrue(UpdateChecker.compareVersions("0.5.0", "0.5.0-debug") == 0)
        assertTrue(UpdateChecker.compareVersions("0.5", "0.5.0") == 0)
        assertTrue(UpdateChecker.compareVersions("0.4.2", "0.5.0") < 0)
        assertEquals("0.5.0", UpdateChecker.normalizeVersion("V0.5.0+9"))
    }

    @Test fun plainNotesKeepsOtherHeadings() {
        assertEquals("Fixes\n• One", UpdateChecker.plainNotes("### Fixes\n* One"))
    }

    @Test fun plainNotes() {
        assertEquals("• Protection tab\n• Fixed crash\n\nSee docs.", UpdateChecker.plainNotes("## What's new\n- **Protection** tab\n- Fixed `crash`\n\n\n\nSee [docs](https://example.com)."))
    }

    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }
    private fun checker() = UpdateChecker("1immortal/truenas-companion", apiBase = server.url("/").toString().trimEnd('/'))

    @Test fun availableAndUpToDate() = runTest {
        server.enqueue(MockResponse().setBody(release("v0.5.1")))
        val r = checker().check("0.5.0")
        assertTrue(r is UpdateResult.Available)
        assertEquals("/repos/1immortal/truenas-companion/releases/latest", server.takeRequest().requestUrl!!.encodedPath)
        server.enqueue(MockResponse().setBody(release("v0.5.0")))
        assertEquals(UpdateResult.UpToDate("0.5.0"), checker().check("0.5.0"))
    }

    @Test fun privateRepoAndRateLimit() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        val r404 = checker().check("0.5.0")
        assertTrue(r404 is UpdateResult.Unavailable && r404.message.contains("private"))
        server.enqueue(MockResponse().setResponseCode(403).addHeader("x-ratelimit-remaining", "0").setBody("""{"message":"API rate limit exceeded"}"""))
        val r403 = checker().check("0.5.0")
        assertTrue(r403 is UpdateResult.Unavailable && r403.message.contains("limit"))
    }

    @Test fun noToken() = runTest {
        server.enqueue(MockResponse().setBody(release()))
        checker().check("0.5.0")
        assertNull(server.takeRequest().headers["Authorization"])
    }
}
