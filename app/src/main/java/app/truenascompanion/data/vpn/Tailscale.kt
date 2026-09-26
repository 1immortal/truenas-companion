package app.truenascompanion.data.vpn

import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** The Tailscale Android app (not embedded: the app only detects it and opens it). */
object TailscaleApp {
    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(VpnSetup.TS_PACKAGE) != null }.getOrDefault(false)

    /** Opens Tailscale, or its Play Store page when it isn't installed. */
    fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(VpnSetup.TS_PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=${VpnSetup.TS_PACKAGE}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${VpnSetup.TS_PACKAGE}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
