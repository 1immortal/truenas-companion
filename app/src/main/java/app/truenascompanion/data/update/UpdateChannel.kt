package app.truenascompanion.data.update

import app.truenascompanion.BuildConfig

/**
 * Which GitHub release APK the in-app updater fetches and which signing certificate it expects.
 *
 * Release and Debug use different package ids (`.debug` suffix) and different signing keys.
 * Switching channels usually means uninstalling the other build first — Android will not replace
 * an app with a different package name or signing certificate.
 */
enum class UpdateChannel {
    RELEASE,
    DEBUG,
    ;

    val label: String
        get() = when (this) {
            RELEASE -> "Release"
            DEBUG -> "Debug"
        }

    /** Unified asset name on every GitHub release (not versioned). */
    val apkAssetName: String
        get() = when (this) {
            RELEASE -> UpdateAssets.RELEASE_APK
            DEBUG -> UpdateAssets.DEBUG_APK
        }

    val expectedSignerSha256: String
        get() = when (this) {
            RELEASE -> BuildConfig.RELEASE_SIGNER_SHA256.lowercase()
            DEBUG -> BuildConfig.DEBUG_SIGNER_SHA256.lowercase()
        }

    companion object {
        /** Default: Release builds track Release; debug builds track Debug. */
        fun defaultForBuild(): UpdateChannel =
            if (BuildConfig.DEBUG) DEBUG else RELEASE

        fun fromStorage(raw: String?): UpdateChannel =
            raw?.let { runCatching { valueOf(it) }.getOrNull() } ?: defaultForBuild()
    }
}

/** Stable GitHub release asset filenames (same on every tag). */
object UpdateAssets {
    const val RELEASE_APK = "truenas-companion-release.apk"
    const val DEBUG_APK = "truenas-companion-debug.apk"

    fun checksumName(apkName: String) = "$apkName.sha256"
}
