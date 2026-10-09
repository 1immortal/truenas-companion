package app.truenascompanion.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 1.8.0 (security review M-1): an AES key that the Keystore only lets the app use right after a strong biometric or
 * screen-lock check. The app lock and "Confirm dangerous actions" pass it to BiometricPrompt as a CryptoObject and
 * accept the prompt only when encrypting a small canary with it succeeds.
 *
 * Returns null (plain prompt, as before) where the key can't be created: no screen lock, no Keystore (tests), or
 * Android 10 and older.
 */
object AuthKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "ytn_app_lock_auth"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val CANARY = "ytn-unlock".toByteArray()

    fun cipherOrNull(): Cipher? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching { init() }.recoverCatching { e ->
            // A new fingerprint was enrolled (or the screen lock removed): start over with a fresh key.
            if (e is KeyPermanentlyInvalidatedException) { delete(); init() } else throw e
        }.getOrNull()
    }

    /** True if the authenticated cipher works (the only proof the user just authenticated). */
    fun verify(cipher: Cipher?): Boolean = cipher != null && runCatching { cipher.doFinal(CANARY).isNotEmpty() }.getOrDefault(false)

    @RequiresApi(30)
    private fun init(): Cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }

    @RequiresApi(30)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return gen.generateKey()
    }

    private fun delete() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }
}
