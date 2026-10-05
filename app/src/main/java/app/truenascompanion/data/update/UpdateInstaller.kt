package app.truenascompanion.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class UpdateVerificationException(message: String) : Exception(message)

/** Downloads a release APK, verifies it (SHA-256, package, channel signer, version) and hands it to the system installer. */
class UpdateInstaller(private val context: Context, private val client: OkHttpClient = UpdateChecker.defaultClient) {

    private val dir get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Downloads to the cache and verifies it. [onProgress] gets 0..1 (or -1 when the size is unknown). */
    suspend fun download(release: ReleaseInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val channel = release.channel
        val expected = release.sha256 ?: release.checksumUrl?.let { fetchChecksum(it) }
            ?: throw UpdateVerificationException("This release has no SHA-256 checksum, so the download can't be verified. Download it from the release page instead.")
        dir.listFiles()?.forEach { it.delete() } // only ever keep one download
        val target = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(release.apkUrl).header("User-Agent", "TrueNAS-Companion-Android").build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Download failed (HTTP ${res.code})")
            val body = res.body
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize ?: -1L
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        read += n
                        onProgress(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            target.delete()
            throw UpdateVerificationException("The download doesn't match its SHA-256 checksum and was deleted.")
        }
        verifyPackage(target, channel)
        target
    }

    private fun fetchChecksum(url: String): String? =
        client.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) null else res.body.string().trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        }

    /** Same package, newer version, signing certificate matching the selected update channel (and the installed app). */
    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File, channel: UpdateChannel) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw UpdateVerificationException("The download isn't a valid Android app.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) {
            apk.delete()
            throw UpdateVerificationException(
                "The download is for a different app (${archive.packageName}). " +
                    "Release and Debug builds use different package names — switching update channels usually requires uninstalling first."
            )
        }
        if (versionCode(archive) <= versionCode(installed)) {
            apk.delete()
            throw UpdateVerificationException("The download isn't newer than the installed version.")
        }
        val apkSigners = signers(archive)
        val installedSigners = signers(installed)
        if (apkSigners.isEmpty()) {
            apk.delete()
            throw UpdateVerificationException("The download has no signing certificate.")
        }
        val expected = channel.expectedSignerSha256
        if (expected !in apkSigners) {
            apk.delete()
            throw UpdateVerificationException(
                "The download isn't signed with the expected ${channel.label} key. " +
                    "Check System › About › Update channel, or download the matching APK from the release page."
            )
        }
        if (apkSigners != installedSigners) {
            apk.delete()
            throw UpdateVerificationException(
                "This update is signed with a different key than the app you have installed. " +
                    "Mixing Release and Debug channels (or upgrading from a 0.x debug build) requires uninstalling first, " +
                    "then installing the APK for your chosen channel. Your servers and settings are wiped by Android when you uninstall."
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } else p.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Android 8+: the user must allow this app to install apps once. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the verified APK to the system package installer (the user confirms there). */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
