package com.limelight.unlock

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists per-host unlock PINs as AES-GCM ciphertext. The Keystore key can only be used after a
 * strong biometric authentication, and is invalidated when the enrolled biometrics change.
 */
object HostUnlockCredentialStore {
    private const val PREFERENCES_NAME = "host_unlock_credentials"
    private const val PREF_KEY_PREFIX = "pin_"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "moonlight_host_unlock_pin_v1"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val FORMAT_VERSION: Byte = 0x01

    fun has(context: Context, hostUuid: String?): Boolean {
        if (hostUuid.isNullOrEmpty()) return false
        return prefs(context).contains(prefKey(hostUuid))
    }

    fun remove(context: Context, hostUuid: String?) {
        if (hostUuid.isNullOrEmpty()) return
        prefs(context).edit().remove(prefKey(hostUuid)).apply()
    }

    /** Drops every stored PIN and the Keystore key, e.g. after biometric enrollment changed. */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    /**
     * Returns a cipher that must be authenticated through BiometricPrompt before [save].
     * Throws [android.security.keystore.KeyPermanentlyInvalidatedException] if biometrics changed.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun createEncryptCipher(): Cipher {
        return Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        }
    }

    /**
     * Returns a cipher that must be authenticated through BiometricPrompt before [decrypt], or
     * null when no valid PIN is stored for the host.
     * Throws [android.security.keystore.KeyPermanentlyInvalidatedException] if biometrics changed.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun createDecryptCipher(context: Context, hostUuid: String): Cipher? {
        val encrypted = readEncrypted(context, hostUuid) ?: return null
        val key = loadSecretKey()
        if (key == null) {
            remove(context, hostUuid)
            return null
        }
        return Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, encrypted, 0, GCM_IV_LENGTH)
            )
        }
    }

    fun save(context: Context, hostUuid: String, authenticatedCipher: Cipher, pin: CharArray): Boolean {
        val plaintext = ByteArray(pin.size) { pin[it].code.toByte() }
        return try {
            authenticatedCipher.updateAAD(aad(hostUuid))
            val ciphertext = authenticatedCipher.doFinal(plaintext)
            val payload = byteArrayOf(FORMAT_VERSION) + authenticatedCipher.iv + ciphertext
            prefs(context).edit()
                .putString(prefKey(hostUuid), Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    /** Returns the decrypted PIN; the caller must wipe the returned array after use. */
    fun decrypt(context: Context, hostUuid: String, authenticatedCipher: Cipher): CharArray? {
        val encrypted = readEncrypted(context, hostUuid) ?: return null
        val plaintext = runCatching {
            authenticatedCipher.updateAAD(aad(hostUuid))
            authenticatedCipher.doFinal(encrypted, GCM_IV_LENGTH, encrypted.size - GCM_IV_LENGTH)
        }.getOrNull() ?: return null
        return try {
            CharArray(plaintext.size) { (plaintext[it].toInt() and 0xff).toChar() }
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    /** Returns IV + ciphertext without the version byte. */
    private fun readEncrypted(context: Context, hostUuid: String): ByteArray? {
        val encoded = prefs(context).getString(prefKey(hostUuid), null) ?: return null
        val payload = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
        if (payload == null || payload.size <= 1 + GCM_IV_LENGTH + GCM_TAG_BITS / 8 ||
            payload[0] != FORMAT_VERSION
        ) {
            remove(context, hostUuid)
            return null
        }
        return payload.copyOfRange(1, payload.size)
    }

    private fun aad(hostUuid: String): ByteArray = hostUuid.toByteArray(Charsets.UTF_8)

    private fun loadSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun getOrCreateSecretKey(): SecretKey {
        loadSecretKey()?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun prefKey(hostUuid: String) = PREF_KEY_PREFIX + hostUuid
}
