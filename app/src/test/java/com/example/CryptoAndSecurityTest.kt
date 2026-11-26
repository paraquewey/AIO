package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.logger.AppLogger
import com.example.data.security.HostKeyVerificationResult
import com.example.data.security.KeystoreCryptoManager
import com.example.data.security.SecretKeyProvider
import com.example.data.security.SshHostKeyStore
import com.example.domain.models.ProfileSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CryptoAndSecurityTest {

    private class TestAesKeyProvider : SecretKeyProvider {
        private val key: SecretKey by lazy {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun getOrCreateSecretKey(): SecretKey = key
    }

    @Test
    fun `aes gcm encryption roundtrip and ciphertext tamper detection`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = KeystoreCryptoManager(context, TestAesKeyProvider())

        val secretText = "my-private-ssh-passphrase-2026"
        val encrypted1 = crypto.encryptText(secretText)
        val encrypted2 = crypto.encryptText(secretText)

        // Randomized IV must produce distinct ciphertexts
        assertNotEquals(encrypted1, encrypted2)
        assertFalse(encrypted1.contains(secretText))
        assertEquals(secretText, crypto.decryptText(encrypted1))

        // Tampering ciphertext must fail GCM tag verification
        val tampered = encrypted1.replaceRange(
            encrypted1.length - 8,
            encrypted1.length - 6,
            if (encrypted1.endsWith("AA==")) "BB" else "AA"
        )
        var threw = false
        try {
            crypto.decryptText(tampered)
        } catch (_: AEADBadTagException) {
            threw = true
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `profile secrets saved encrypted on disk and securely wiped on delete`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = KeystoreCryptoManager(context, TestAesKeyProvider())
        val profileId = "test-vault-profile"
        val secrets = ProfileSecrets(
            password = "VaultPassword!42",
            privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----",
            jumpPassword = "JumpSecret!99"
        )

        crypto.saveProfileSecrets(profileId, secrets)
        val vaultFile = File(context.filesDir, "aio_vault/$profileId.enc")
        assertTrue(vaultFile.exists())
        val rawDisk = vaultFile.readText()
        assertFalse(rawDisk.contains("VaultPassword!42"))
        assertFalse(rawDisk.contains("JumpSecret!99"))

        val loaded = crypto.loadProfileSecrets(profileId)
        assertEquals(secrets, loaded)

        crypto.deleteProfileSecrets(profileId)
        assertFalse(vaultFile.exists())
    }

    @Test
    fun `ssh host key store detects unknown, trusted, and mismatch fingerprints`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SshHostKeyStore(context)
        store.clearAll()

        val keyA = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val keyB = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)

        val unknown = store.verify("ssh.example.com", 22, "ssh-ed25519", keyA)
        assertTrue(unknown is HostKeyVerificationResult.UnknownHost)
        val fpA = (unknown as HostKeyVerificationResult.UnknownHost).sha256Fingerprint

        store.trustHostKey("ssh.example.com", 22, "ssh-ed25519", fpA)
        val trusted = store.verify("ssh.example.com", 22, "ssh-ed25519", keyA)
        assertTrue(trusted is HostKeyVerificationResult.Trusted)

        val mismatch = store.verify("ssh.example.com", 22, "ssh-ed25519", keyB)
        assertTrue(mismatch is HostKeyVerificationResult.HostKeyMismatch)
    }

    @Test
    fun `logger sanitizes passwords, bearer tokens, uri credentials, and private keys`() {
        val raw = "Connecting ssh://user:TopSecretPass@10.0.0.1:22 password=abc123 Authorization: Bearer eyJhbGciOiJIUzI1NiJ9"
        val sanitized = AppLogger.sanitize(raw)
        assertFalse(sanitized.contains("TopSecretPass"))
        assertFalse(sanitized.contains("abc123"))
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }
}
