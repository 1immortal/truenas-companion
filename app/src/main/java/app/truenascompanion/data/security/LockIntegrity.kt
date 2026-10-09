package app.truenascompanion.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * 1.8.0 (security review M-1): the app-lock settings are stored with an HMAC from a Keystore key, so switching the
 * lock off by editing the settings file (rooted or instrumented phone) is detected. A wrong or missing tag while the
 * key exists keeps the lock on ("fail closed"). Settings from before 1.8.0, a restore onto a new phone, or a phone
 * without a Keystore (tests) have no key yet and are accepted, then signed on the next save.
 */
object LockIntegrity {
    enum class Verdict { OK, UNSIGNED, TAMPERED }

    /** Keystore access, replaceable in tests. */
    interface Signer {
        fun hasKey(): Boolean
        /** HMAC of [data], creating the key if needed; null when there's no Keystore. */
        fun sign(data: String): String?
    }

    var signer: Signer = KeystoreSigner

    fun verdict(json: String?, tag: String?): Verdict {
        if (json == null) return Verdict.OK
        if (!signer.hasKey()) return Verdict.UNSIGNED
        val expected = signer.sign(json) ?: return Verdict.UNSIGNED
        return if (tag != null && java.security.MessageDigest.isEqual(expected.toByteArray(), tag.toByteArray())) Verdict.OK else Verdict.TAMPERED
    }

    /** What the app uses when the stored settings fail the check: lock on, everything protected. */
    fun failClosed(stored: LockSettings?): LockSettings =
        (stored ?: LockSettings()).copy(enabled = true, confirmDangerous = true, privacyScreen = true)

    private object KeystoreSigner : Signer {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "ytn_lock_settings_hmac"

        private fun store(): KeyStore? = runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) } }.getOrNull()

        override fun hasKey(): Boolean = store()?.containsAlias(ALIAS) == true

        @Synchronized
        override fun sign(data: String): String? = runCatching {
            val ks = store() ?: return null
            val key = (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
                generateKey()
            }
            val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
            Base64.encodeToString(mac.doFinal(data.toByteArray()), Base64.NO_WRAP)
        }.getOrNull()
    }
}
