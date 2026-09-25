package app.truenascompanion.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object PortalUrls {
    /**
     * TrueNAS builds portal links from the address the middleware saw (the NAS's own IP). The app rewrites the host to
     * the address it is using right now (local IP at home, remote hostname away), keeping the portal's scheme, port
     * and path.
     */
    fun rewrite(portal: String, currentBaseUrl: String?): String {
        val p = portal.toHttpUrlOrNull() ?: return portal
        val host = currentBaseUrl?.toHttpUrlOrNull()?.host ?: return portal
        return p.newBuilder().host(host).build().toString()
    }
}
