package app.truenascompanion.notify

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.content.edit
import app.truenascompanion.PendingDeepLink
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 1.7.1 (security M-2): MainActivity is exported (it is the launcher activity), so any app can start it with extras.
 * Intents the app builds itself (notifications, tiles, dynamic shortcuts, update notice) carry an HMAC of their
 * destination, server and argument, keyed with a random per-install secret that never leaves the app. Only those may
 * switch the server or preselect a target (a pool to scrub, an app to restart). Unsigned intents (other apps, the static
 * launcher shortcuts) can only open a few plain screens; every action still asks for confirmation as before.
 */
object DeepLinkGuard {
    const val EXTRA_SIG = "app.truenascompanion.extra.SIG"
    private const val PREFS = "deep_link_guard"
    private const val KEY = "secret"

    /** Screens an unsigned intent may open (no server switch, no argument). */
    val PUBLIC: Set<String> = setOf(DeepLink.DEST_ALERTS, DeepLink.DEST_SHELL, DeepLink.DEST_RESTART_APP, DeepLink.DEST_SCRUB_POOL, DeepLink.DEST_DASHBOARD)

    @Volatile private var cached: ByteArray? = null

    private fun secret(context: Context): ByteArray = cached ?: synchronized(this) {
        cached ?: run {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            existing?.takeIf { it.size == 32 } ?: ByteArray(32).also {
                SecureRandom().nextBytes(it)
                prefs.edit { putString(KEY, Base64.encodeToString(it, Base64.NO_WRAP)) }
            }
        }.also { cached = it }
    }

    /** HMAC-SHA256 over the three fields (pure, unit tested). */
    fun mac(secret: ByteArray, destination: String, serverId: String?, arg: String?): String {
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        val bytes = m.doFinal(listOf(destination, serverId.orEmpty(), arg.orEmpty()).joinToString("\u0000").toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Adds the signature to an intent built by the app (call after all extras are set). */
    fun sign(context: Context, intent: Intent): Intent = intent.apply {
        val dest = getStringExtra(DeepLink.EXTRA_DESTINATION) ?: return@apply
        putExtra(EXTRA_SIG, mac(secret(context), dest, getStringExtra(DeepLink.EXTRA_SERVER_ID), getStringExtra(DeepLink.EXTRA_ARG)))
    }

    /** Reads an incoming intent; malformed extras are ignored instead of crashing the app. */
    fun parse(context: Context, intent: Intent?): PendingDeepLink? = runCatching {
        val i = intent ?: return null
        val dest = i.getStringExtra(DeepLink.EXTRA_DESTINATION) ?: return null
        val server = i.getStringExtra(DeepLink.EXTRA_SERVER_ID)
        val arg = i.getStringExtra(DeepLink.EXTRA_ARG)
        val sig = i.getStringExtra(EXTRA_SIG)
        decide(dest, server, arg, sig?.let { verify(mac(secret(context), dest, server, arg), it) } == true)
    }.getOrNull()

    /** Pure decision (unit tested): signed links pass as they are; unsigned ones only open a public screen. */
    fun decide(destination: String, serverId: String?, arg: String?, signed: Boolean): PendingDeepLink? = when {
        destination.length > 64 || (arg?.length ?: 0) > 512 || (serverId?.length ?: 0) > 64 -> null
        signed -> PendingDeepLink(serverId, destination, arg = arg)
        destination in PUBLIC -> PendingDeepLink(null, destination)
        else -> null
    }

    private fun verify(expected: String, actual: String) = MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
}
