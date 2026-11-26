package com.example.domain.models

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Preparing(val profileName: String) : ConnectionState()
    data class Connecting(val profileName: String, val detail: String = "") : ConnectionState()
    data class Connected(
        val profileId: String,
        val profileName: String,
        val protocol: TunnelProtocol,
        val serverEndpoint: String,
        val exitIp: String? = null,
        val exitCountry: String? = null,
        val exitCountryCode: String? = null,
        val exitCity: String? = null,
        val latencyMs: Long? = null,
        val connectedSinceMillis: Long = System.currentTimeMillis(),
        val serverMessageMode: ServerMessageMode = ServerMessageMode.ORIGINAL,
        val customServerMessageHtml: String = "",
        val originalServerBanner: String? = null
    ) : ConnectionState()
    data class Reconnecting(val profileName: String, val attempt: Int, val reason: String) : ConnectionState()
    data class Disconnecting(val profileName: String) : ConnectionState()
    data class Error(val message: String, val recoverable: Boolean = true) : ConnectionState()

    val isActiveOrTransitioning: Boolean
        get() = this is Preparing || this is Connecting || this is Connected || this is Reconnecting || this is Disconnecting
}

data class SshHostKeyChallenge(
    val host: String,
    val port: Int,
    val keyType: String,
    val sha256Fingerprint: String,
    val isMismatchWarning: Boolean,
    val previousFingerprint: String? = null
)
