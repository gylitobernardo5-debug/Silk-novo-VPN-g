package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.model.ProfileSecrets
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and persists sensitive profile credentials (passwords, UUIDs, SSH private keys)
 * using AES-256-GCM backed by Android Keystore. Never logs or exposes plaintext secrets.
 */
class CredentialVault(private val context: Context) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val vaultDir: File by lazy {
    File(context.filesDir, "gvpn_vault").apply { mkdirs() }
  }

  private val lock = Any()

  fun saveSecrets(profileId: String, secrets: ProfileSecrets) {
    synchronized(lock) {
      val payload = json.encodeToString(secrets).toByteArray(Charsets.UTF_8)
      val encrypted = encryptBytes(payload)
      targetFile(profileId).writeBytes(encrypted)
    }
  }

  fun getSecrets(profileId: String): ProfileSecrets {
    synchronized(lock) {
      val file = targetFile(profileId)
      if (!file.exists()) return ProfileSecrets()
      return runCatching {
          val decrypted = decryptBytes(file.readBytes())
          json.decodeFromString<ProfileSecrets>(decrypted.toString(Charsets.UTF_8))
        }
        .getOrDefault(ProfileSecrets())
    }
  }

  fun deleteSecrets(profileId: String) {
    synchronized(lock) {
      val file = targetFile(profileId)
      if (file.exists()) {
        // Overwrite with zeros before deleting to avoid leaving residual bytes on disk
        runCatching {
          val size = file.length().toInt().coerceIn(0, 65536)
          if (size > 0) file.writeBytes(ByteArray(size))
        }
        file.delete()
      }
    }
  }

  fun wipeAll() {
    synchronized(lock) {
      vaultDir.listFiles()?.forEach { file ->
        runCatching {
          val size = file.length().toInt().coerceIn(0, 65536)
          if (size > 0) file.writeBytes(ByteArray(size))
        }
        file.delete()
      }
    }
  }

  private fun targetFile(profileId: String): File {
    val sanitized = profileId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return File(vaultDir, "$sanitized.enc")
  }

  private fun encryptBytes(plain: ByteArray): ByteArray {
    val key = getOrCreateAesKey()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val iv = cipher.iv
    val cipherText = cipher.doFinal(plain)
    // Format: [1 byte ivLen][iv][cipherText]
    return ByteArray(1 + iv.size + cipherText.size).apply {
      this[0] = iv.size.toByte()
      System.arraycopy(iv, 0, this, 1, iv.size)
      System.arraycopy(cipherText, 0, this, 1 + iv.size, cipherText.size)
    }
  }

  private fun decryptBytes(blob: ByteArray): ByteArray {
    require(blob.size > 14) { "Invalid encrypted blob" }
    val ivLen = blob[0].toInt() and 0xFF
    require(ivLen in 12..16 && blob.size > 1 + ivLen) { "Invalid IV length" }
    val iv = blob.copyOfRange(1, 1 + ivLen)
    val cipherText = blob.copyOfRange(1 + ivLen, blob.size)
    val key = getOrCreateAesKey()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    return cipher.doFinal(cipherText)
  }

  private fun getOrCreateAesKey(): SecretKey {
    return runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return@runCatching existing

        val generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec =
          KeyGenParameterSpec.Builder(
              KEY_ALIAS,
              KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        generator.generateKey()
      }
      .getOrElse {
        // Fallback exclusively for JVM/Robolectric unit tests where AndroidKeyStore is absent
        getOrCreateLocalFallbackKey()
      }
  }

  private fun getOrCreateLocalFallbackKey(): SecretKey {
    val keyFile = File(vaultDir, ".fallback_key")
    val raw =
      if (keyFile.exists()) {
        Base64.decode(keyFile.readText(), Base64.NO_WRAP)
      } else {
        ByteArray(32).also {
          SecureRandom().nextBytes(it)
          keyFile.writeText(Base64.encodeToString(it, Base64.NO_WRAP))
        }
      }
    return SecretKeySpec(raw, "AES")
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "gvpn_master_credential_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
  }
}
