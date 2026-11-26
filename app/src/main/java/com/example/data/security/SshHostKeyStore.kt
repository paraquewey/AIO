package com.example.data.security

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

sealed class HostKeyVerificationResult {
    data object Trusted : HostKeyVerificationResult()
    data class UnknownHost(val sha256Fingerprint: String) : HostKeyVerificationResult()
    data class HostKeyMismatch(
        val expectedFingerprint: String,
        val actualFingerprint: String
    ) : HostKeyVerificationResult()
}

class SshHostKeyStore(context: Context) {
    private val file = File(context.filesDir, "ssh_known_hosts.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()

    @Serializable
    data class KnownHostRecord(
        val endpoint: String,
        val keyType: String,
        val sha256Fingerprint: String,
        val trustedAtMillis: Long
    )

    fun computeSha256Fingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val b64 = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
            .trimEnd('=')
        return "SHA256:$b64"
    }

    fun verify(host: String, port: Int, keyType: String, publicKeyBytes: ByteArray): HostKeyVerificationResult {
        val endpoint = "$host:$port"
        val fingerprint = computeSha256Fingerprint(publicKeyBytes)
        val existing = getAllRecords().firstOrNull { it.endpoint == endpoint && it.keyType == keyType }
            ?: getAllRecords().firstOrNull { it.endpoint == endpoint }
        return when {
            existing == null -> HostKeyVerificationResult.UnknownHost(fingerprint)
            existing.sha256Fingerprint == fingerprint -> HostKeyVerificationResult.Trusted
            else -> HostKeyVerificationResult.HostKeyMismatch(
                expectedFingerprint = existing.sha256Fingerprint,
                actualFingerprint = fingerprint
            )
        }
    }

    fun trustHostKey(host: String, port: Int, keyType: String, sha256Fingerprint: String) {
        val endpoint = "$host:$port"
        synchronized(lock) {
            val updated = getAllRecords()
                .filterNot { it.endpoint == endpoint }
                .toMutableList()
            updated.add(
                KnownHostRecord(
                    endpoint = endpoint,
                    keyType = keyType,
                    sha256Fingerprint = sha256Fingerprint,
                    trustedAtMillis = System.currentTimeMillis()
                )
            )
            file.writeText(json.encodeToString(updated))
        }
    }

    fun removeHostKey(host: String, port: Int) {
        val endpoint = "$host:$port"
        synchronized(lock) {
            val updated = getAllRecords().filterNot { it.endpoint == endpoint }
            file.writeText(json.encodeToString(updated))
        }
    }

    fun getAllRecords(): List<KnownHostRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<KnownHostRecord>>(file.readText())
        }.getOrDefault(emptyList())
    }

    fun clearAll() = synchronized(lock) {
        if (file.exists()) file.delete()
    }
}
