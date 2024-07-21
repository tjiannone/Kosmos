package com.github.shadowsocks.utils

import android.util.Base64
import android.util.Log
import java.nio.charset.Charset
import java.security.Key
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

object EncryptionUtils {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"
    private val CHARSET: Charset = Charsets.UTF_8
    private const val KEY_SIZE = 256

    fun generateKey(): Key {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
        keyGenerator.init(KEY_SIZE, SecureRandom())
        return SecretKeySpec(keyGenerator.generateKey().encoded, ALGORITHM)
    }

    fun encryptProfile(profile: String, key: Key): String {
        return try {
            val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encryptedValue = cipher.doFinal(profile.toByteArray(CHARSET))
            Base64.encodeToString(encryptedValue, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e("EncryptionUtils", "Error encrypting profile", e)
            ""
        }
    }

    fun decryptProfile(encryptedProfile: String, key: Key): String {
        return try {
            val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decodedValue = Base64.decode(encryptedProfile, Base64.DEFAULT)
            val decryptedValue = cipher.doFinal(decodedValue)
            String(decryptedValue, CHARSET)
        } catch (e: IllegalArgumentException) {
            Log.e("EncryptionUtils", "Bad Base64 encoding", e)
            ""
        } catch (e: Exception) {
            Log.e("EncryptionUtils", "Error decrypting profile", e)
            ""
        }
    }
}
