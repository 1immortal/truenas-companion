package app.truenascompanion

import app.truenascompanion.data.update.UpdateAssets
import app.truenascompanion.data.update.UpdateChannel
import app.truenascompanion.data.update.UpdateChecker
import app.truenascompanion.data.update.UpdateResult
import app.truenascompanion.ui.servers.maskCertificateFingerprint
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

    private fun release(
        tag: String = "v1.0.3",
        digest: String? = "sha256:$sha",
        assetsJson: String? = null,
    ): String {
        val assets = assetsJson ?: """
            {"name":"${UpdateAssets.RELEASE_APK}","size":12345678,
             "browser_download_url":"https://github.com/x/y/releases/download/$tag/${UpdateAssets.RELEASE_APK}"${digest?.let { ",\"digest\":\"$it\"" } ?: ""}},
            {"name":"${UpdateAssets.DEBUG_APK}","size":12345679,
             "browser_download_url":"https://github.com/x/y/releases/download/$tag/${UpdateAssets.DEBUG_APK}","digest":"sha256:${"b".repeat(64)}"},
            {"name":"${UpdateAssets.RELEASE_APK}.sha256","browser_download_url":"https://x/release.sum"},
            {"name":"${UpdateAssets.DEBUG_APK}.sha256","browser_download_url":"https://x/debug.sum"}
        """.trimIndent()
        return """
        {"tag_name":"$tag","name":"TrueNAS Companion $tag","html_url":"https://github.com/1immortal/ytn/releases/tag/$tag",
         "body":"## What's new\n- **Protection** tab\n- Fixed `crash`\n\nSee [docs](https://example.com).",
         "assets":[$assets]}
        """.trimIndent()
    }

    @Test fun picksUnifiedReleaseAsset() {
        val r = UpdateChecker.parseRelease(release(), UpdateChannel.RELEASE)!!
        assertEquals("1.0.3", r.version)
        assertEquals(UpdateAssets.RELEASE_APK, r.apkName)
        assertEquals(sha, r.sha256)
        assertEquals(UpdateChannel.RELEASE, r.channel)
    }

    @Test fun picksUnifiedDebugAsset() {
        val r = UpdateChecker.parseRelease(release(), UpdateChannel.DEBUG)!!
        assertEquals(UpdateAssets.DEBUG_APK, r.apkName)
        assertEquals("b".repeat(64), r.sha256)
        assertEquals(UpdateChannel.DEBUG, r.channel)
    }

    @Test fun fallsBackToDeprecatedVersionedNames() {
        val legacy = release(
            tag = "v1.0.2",
            assetsJson = """
                {"name":"truenas-companion-v1.0.2.apk","size":1,"browser_download_url":"https://x/r.apk","digest":"sha256:$sha"},
                {"name":"truenas-companion-v1.0.2-debug.apk","size":2,"browser_download_url":"https://x/d.apk","digest":"sha256:${"c".repeat(64)}"},
                {"name":"truenas-companion-v1.0.2.apk.sha256","browser_download_url":"https://x/r.sum"}
            """.trimIndent(),
        )
        assertEquals("truenas-companion-v1.0.2.apk", UpdateChecker.parseRelease(legacy, UpdateChannel.RELEASE)!!.apkName)
        assertEquals("truenas-companion-v1.0.2-debug.apk", UpdateChecker.parseRelease(legacy, UpdateChannel.DEBUG)!!.apkName)
    }

    @Test fun fallsBackToChecksumAsset() {
        val body = release(
            digest = null,
            assetsJson = """
                {"name":"${UpdateAssets.RELEASE_APK}","size":1,"browser_download_url":"https://x/a.apk"},
                {"name":"${UpdateAssets.RELEASE_APK}.sha256","browser_download_url":"https://x/sum"}
            """.trimIndent(),
        )
        val r = UpdateChecker.parseRelease(body, UpdateChannel.RELEASE)!!
        assertNull(r.sha256)
        assertEquals("https://x/sum", r.checksumUrl)
    }

    @Test fun noMatchingApkMeansNoRelease() {
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"v1.0.0","assets":[{"name":"notes.txt","browser_download_url":"x"}]}""", UpdateChannel.RELEASE))
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

    @Test fun maskHidesFingerprintDigits() {
        val fp = "92fb06a9b958c4a08f0db43e1e4b9b2c6ee0a9cdf4a91ffd2421a11277f5e608"
        val masked = maskCertificateFingerprint(fp)
        assertTrue(masked.all { it == '•' })
        assertEquals(64, masked.length)
        assertTrue(!masked.contains("92fb"))
    }

    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }
    private fun checker() = UpdateChecker("1immortal/ytn", apiBase = server.url("/").toString().trimEnd('/'))

    @Test fun availableAndUpToDate() = runTest {
        server.enqueue(MockResponse().setBody(release("v1.0.3")))
        val r = checker().check("1.0.2", UpdateChannel.RELEASE)
        assertTrue(r is UpdateResult.Available)
        assertEquals(UpdateAssets.RELEASE_APK, (r as UpdateResult.Available).release.apkName)
        assertEquals("/repos/1immortal/ytn/releases/latest", server.takeRequest().requestUrl!!.encodedPath)
        server.enqueue(MockResponse().setBody(release("v1.0.3")))
        assertEquals(UpdateResult.UpToDate("1.0.3"), checker().check("1.0.3", UpdateChannel.RELEASE))
    }

    @Test fun privateRepoAndRateLimit() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        val r404 = checker().check("1.0.0", UpdateChannel.RELEASE)
        assertTrue(r404 is UpdateResult.Unavailable && r404.message.contains("private"))
        server.enqueue(MockResponse().setResponseCode(403).addHeader("x-ratelimit-remaining", "0").setBody("""{"message":"API rate limit exceeded"}"""))
        val r403 = checker().check("1.0.0", UpdateChannel.RELEASE)
        assertTrue(r403 is UpdateResult.Unavailable && r403.message.contains("limit"))
    }

    @Test fun noToken() = runTest {
        server.enqueue(MockResponse().setBody(release()))
        checker().check("1.0.0", UpdateChannel.RELEASE)
        assertNull(server.takeRequest().headers["Authorization"])
    }
}
