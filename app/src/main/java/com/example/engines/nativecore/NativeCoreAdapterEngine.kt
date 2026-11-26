package com.example.engines.nativecore

import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelStatistics
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.EngineStatus
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Clean Phase C adapter contract for Go/C native proxy cores (sing-box / Xray-core / Slipstream / dnstt).
 * This environment compiles pure Android/Kotlin without a prebuilt Go/NDK AAR (`libbox.aar` / `libxray.aar`).
 * Per project non-negotiable rules, this adapter NEVER simulates a connection: it validates configuration
 * and reports the exact missing native core artifact until the AAR is linked.
 */
class NativeCoreAdapterEngine : TunnelEngine {
    override val engineFamily: EngineFamily = EngineFamily.NATIVE_CORE
    override val engineName: String = "Native Core Adapter (sing-box / Xray / Slipstream)"

    private val _status = MutableStateFlow(EngineStatus.IDLE)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()
    private var context: EngineContext? = null

    fun isNativeLibraryLinked(): Boolean {
        return runCatching {
            Class.forName("io.nekohasekai.libbox.Libbox")
            true
        }.getOrElse {
            runCatching {
                Class.forName("com.xray.XrayCore")
                true
            }.getOrDefault(false)
        }
    }

    override suspend fun validateConfiguration(
        profile: TunnelProfile,
        secrets: ProfileSecrets
    ): Result<Unit> {
        if (profile.host.isBlank()) {
            return Result.failure(IllegalArgumentException("Host remoto requerido."))
        }
        if (profile.port !in 1..65535) {
            return Result.failure(IllegalArgumentException("Puerto inválido (1-65535)."))
        }
        return Result.success(Unit)
    }

    override suspend fun initialize(context: EngineContext) {
        this.context = context
        _status.value = EngineStatus.INITIALIZED
    }

    override suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets): Result<Unit> {
        val validation = validateConfiguration(profile, secrets)
        if (validation.isFailure) return validation

        if (!isNativeLibraryLinked()) {
            _status.value = EngineStatus.ERROR
            val requirement = profile.protocol.requirementNote
                ?: "Requiere librería nativa Go/C++ compilada (.aar/.so)."
            return Result.failure(
                UnsupportedOperationException(
                    "El protocolo ${profile.protocol.displayName} no está integrado en este APK porque no se permite simular túneles. $requirement Usa HTTP, HTTPS, SOCKS5 o SSH/Jump Host para conexiones reales inmediatas."
                )
            )
        }
        _status.value = EngineStatus.ERROR
        return Result.failure(IllegalStateException("Núcleo nativo detectado sin inicializador de plataforma."))
    }

    override suspend fun stop() {
        _status.value = EngineStatus.IDLE
    }

    override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream {
        throw IOException("Núcleo nativo no disponible para $targetHost:$targetPort.")
    }

    override fun getStatistics(): TunnelStatistics =
        context?.statisticsManager?.stats?.value ?: TunnelStatistics()

    override fun release() {
        _status.value = EngineStatus.IDLE
    }
}
