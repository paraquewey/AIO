package com.example.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.domain.models.ProfileSecrets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretKeyProvider {
    fun getOrCreateSecretKey(): SecretKey
}

class AndroidKeystoreKeyProvider(
    private val alias: String = "aio_tunnel_master_aes_gcm_v1"
) : SecretKeyProvider {
    override fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}

class KeystoreCryptoManager(
    context: Context,
    private val keyProvider: SecretKeyProvider = AndroidKeystoreKeyProvider()
) {
    private val vaultDir = File(context.filesDir, "aio_vault").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class EncryptedBlob(
        val ivBase64: String,
        val ciphertextBase64: String
    )

    @Serializable
    private data class SecretPayload(
        val password: String = "",
        val privateKeyPem: String = "",
        val keyPassphrase: String = "",
        val jumpPassword: String = "",
        val jumpPrivateKeyPem: String = ""
    )

    fun encryptText(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val key = keyProvider.getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val blob = EncryptedBlob(
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertextBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        )
        return json.encodeToString(blob)
    }

    fun decryptText(encryptedJson: String): String {
        if (encryptedJson.isBlank()) return ""
        val blob = json.decodeFromString<EncryptedBlob>(encryptedJson)
        val key = keyProvider.getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = Base64.decode(blob.ivBase64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(blob.ciphertextBase64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(ciphertext)
        return try {
            String(plainBytes, StandardCharsets.UTF_8)
        } finally {
            plainBytes.fill(0)
        }
    }

    fun saveProfileSecrets(profileId: String, secrets: ProfileSecrets) {
        val file = secretFileFor(profileId)
        if (!secrets.hasAnySecret()) {
            secureDeleteFile(file)
            return
        }
        val payload = SecretPayload(
            password = secrets.password,
            privateKeyPem = secrets.privateKeyPem,
            keyPassphrase = secrets.keyPassphrase,
            jumpPassword = secrets.jumpPassword,
            jumpPrivateKeyPem = secrets.jumpPrivateKeyPem
        )
        val encoded = json.encodeToString(payload)
        val encrypted = encryptText(encoded)
        file.writeText(encrypted, StandardCharsets.UTF_8)
    }

    fun loadProfileSecrets(profileId: String): ProfileSecrets {
        val file = secretFileFor(profileId)
        if (!file.exists()) return ProfileSecrets()
        return try {
            val decrypted = decryptText(file.readText(StandardCharsets.UTF_8))
            val payload = json.decodeFromString<SecretPayload>(decrypted)
            ProfileSecrets(
                password = payload.password,
                privateKeyPem = payload.privateKeyPem,
                keyPassphrase = payload.keyPassphrase,
                jumpPassword = payload.jumpPassword,
                jumpPrivateKeyPem = payload.jumpPrivateKeyPem
            )
        } catch (_: Exception) {
            ProfileSecrets()
        }
    }

    fun deleteProfileSecrets(profileId: String) {
        secureDeleteFile(secretFileFor(profileId))
    }

    fun isKeystoreHardwareAvailable(): Boolean = try {
        keyProvider.getOrCreateSecretKey()
        true
    } catch (_: Exception) {
        false
    }

    private fun secretFileFor(profileId: String): File {
        val safeName = profileId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(vaultDir, "$safeName.enc")
    }

    private fun secureDeleteFile(file: File) {
        if (!file.exists()) return
        runCatching {
            val length = file.length().toInt().coerceIn(0, 1024 * 1024)
            if (length > 0) {
                val random = ByteArray(length)
                SecureRandom().nextBytes(random)
                file.writeBytes(random)
                random.fill(0)
            }
            file.delete()
        }
    }
}
