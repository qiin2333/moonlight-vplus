package com.limelight.binding.input.driver.wireless.hci

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists BR/EDR Link Keys as AES-GCM ciphertext protected by Android Keystore. */
@RequiresApi(Build.VERSION_CODES.M)
internal class AndroidKeystoreHciLinkKeyStore(context: Context) : HciLinkKeyStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun load(address: HciBluetoothAddress): HciLinkKey? {
        val preferenceKey = address.preferenceKey()
        val encoded = preferences.getString(preferenceKey, null) ?: return null
        val encrypted = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return removeCorrupt(preferenceKey)
        if (encrypted.size <= GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES) {
            return removeCorrupt(preferenceKey)
        }

        val iv = encrypted.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encrypted.copyOfRange(GCM_IV_LENGTH, encrypted.size)
        val plaintext = runCatching {
            Cipher.getInstance(CIPHER_TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(address.toLittleEndianByteArray())
                doFinal(ciphertext)
            }
        }.getOrNull() ?: return removeCorrupt(preferenceKey)

        if (plaintext.size != SERIALIZED_KEY_LENGTH || plaintext[0] != FORMAT_VERSION) {
            return removeCorrupt(preferenceKey)
        }
        val type = plaintext[1].toInt() and 0xff
        if (type !in 0x00..0x08) return removeCorrupt(preferenceKey)
        return HciLinkKey(plaintext.copyOfRange(2, plaintext.size), type)
    }

    @Synchronized
    override fun save(address: HciBluetoothAddress, key: HciLinkKey) {
        val plaintext = byteArrayOf(FORMAT_VERSION, key.type.toByte()) + key.value
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            updateAAD(address.toLittleEndianByteArray())
        }
        val ciphertext = cipher.doFinal(plaintext)
        val encoded = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        check(preferences.edit().putString(address.preferenceKey(), encoded).commit()) {
            "Unable to persist encrypted Bluetooth Link Key"
        }
    }

    @Synchronized
    override fun remove(address: HciBluetoothAddress) {
        check(preferences.edit().remove(address.preferenceKey()).commit()) {
            "Unable to remove encrypted Bluetooth Link Key"
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKey()
        }
    }

    private fun removeCorrupt(preferenceKey: String): HciLinkKey? {
        preferences.edit().remove(preferenceKey).commit()
        return null
    }

    private fun HciBluetoothAddress.preferenceKey(): String {
        return "link_key_" + value.toString(16).padStart(12, '0')
    }

    companion object {
        private const val PREFERENCES_NAME = "dualsense_wireless_link_keys"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "moonlight_dualsense_wireless_link_key_v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_BITS / 8
        private const val FORMAT_VERSION: Byte = 0x01
        private const val SERIALIZED_KEY_LENGTH = 2 + HciLinkKey.LINK_KEY_LENGTH
    }
}
