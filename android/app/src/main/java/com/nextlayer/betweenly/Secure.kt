package com.nextlayer.betweenly

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps small secrets (the pairing session) so they are not stored as raw bytes.
 *
 * One AES-256-GCM key lives in the Android Keystore under the alias
 * "betweenly_wrap". The key does not require user authentication, so a
 * background location alarm can unwrap the session while the screen is off
 * (after the device has been unlocked once since boot). Each value is stored
 * as base64(iv + ciphertext) in the private SharedPreferences file "secure".
 *
 * [encrypt] and [decrypt] use the same key for chat message JSON and attachment
 * files. Those blobs are not written into these preferences.
 *
 * DATA-FLOW: nothing here touches the network. Plaintext exists only in memory
 * while a caller is using it.
 */
object Secure {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "betweenly_wrap"
    private const val PREFS = "secure"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Encrypts [bytes] with the Keystore key. The result is `iv || ciphertext`
     * (12-byte IV, then AES-GCM ciphertext including the tag), or null if the
     * key is unavailable. Does not write to disk.
     */
    fun encrypt(bytes: ByteArray): ByteArray? = synchronized(lock) { encryptRaw(bytes) }

    /** Opens a buffer produced by [encrypt], or null if it is missing, short, or tampered. */
    fun decrypt(packed: ByteArray): ByteArray? = synchronized(lock) { decryptRaw(packed) }

    /**
     * Encrypt [bytes] and store them under [name]. Failures are swallowed so a
     * Keystore problem cannot crash the process; [get] then returns null.
     */
    fun put(context: Context, name: String, bytes: ByteArray) {
        synchronized(lock) {
            try {
                val packed = encryptRaw(bytes) ?: return
                val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
                prefs(context).edit().putString(name, encoded).commit()
            } catch (_: Exception) {
                // Leave any previous value in place. Callers verify with get.
            }
        }
    }

    /** Decrypt the value stored under [name], or null if it is missing or cannot be opened. */
    fun get(context: Context, name: String): ByteArray? {
        synchronized(lock) {
            return try {
                val encoded = prefs(context).getString(name, null) ?: return null
                val packed = Base64.decode(encoded, Base64.NO_WRAP)
                decryptRaw(packed)
            } catch (_: Exception) {
                // Includes a locked Keystore or a tampered value. Do not delete:
                // a transient failure must not wipe the session.
                null
            }
        }
    }

    private fun encryptRaw(bytes: ByteArray): ByteArray? {
        return try {
            val secret = key() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_LEN) return null
            val ct = cipher.doFinal(bytes)
            val packed = ByteArray(iv.size + ct.size)
            iv.copyInto(packed)
            ct.copyInto(packed, destinationOffset = iv.size)
            packed
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun decryptRaw(packed: ByteArray): ByteArray? {
        return try {
            if (packed.size < IV_LEN + TAG_BITS / 8) return null
            val secret = key() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, IV_LEN))
            cipher.init(Cipher.DECRYPT_MODE, secret, spec)
            cipher.doFinal(packed, IV_LEN, packed.size - IV_LEN)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    fun remove(context: Context, name: String) {
        synchronized(lock) {
            try {
                prefs(context).edit().remove(name).commit()
            } catch (_: Exception) {
                // Nothing else can be done; the next get returns null if the file is gone.
            }
        }
    }

    private fun key(): SecretKey? {
        return try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE)
            store.load(null)
            val existing = store.getKey(ALIAS, null) as? SecretKey
            if (existing != null) return existing
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            generator.generateKey()
        } catch (_: Exception) {
            null
        }
    }
}
