package com.example.core.network

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import com.example.core.logger.AppLogger
import com.example.domain.models.AppSettings
import com.example.domain.models.EngineTuningConfig
import com.example.domain.models.NetworkPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SignalQualityTier(val label: String) {
    UNKNOWN("Normal"),
    WEAK("Débil"),
    MODERATE("Media"),
    STRONG("Fuerte")
}

data class HardwareProfile(
    val cpuCores: Int,
    val availableRamMb: Long,
    val totalRamMb: Long,
    val isLowMemory: Boolean,
    val ramLabel: String
)

data class SignalSnapshot(
    val transportLabel: String,
    val isWifi: Boolean,
    val isCellular: Boolean,
    val signalBars: Int,
    val tier: SignalQualityTier,
    val recentLatencyMs: Long? = null,
    val recentJitterMs: Long? = null
)

data class AdaptiveTuningResult(
    val effectiveConfig: EngineTuningConfig,
    val summaryText: String,
    val hardware: HardwareProfile,
    val signal: SignalSnapshot
)

class AdaptiveNetworkTuner(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var lastPreset: NetworkPreset = NetworkPreset.BALANCED

    @Volatile
    private var lastSettings: AppSettings = AppSettings()

    @Volatile
    private var lastLatencyMs: Long? = null

    @Volatile
    private var lastJitterMs: Long? = null

    @Volatile
    private var registered = false

    private val _state = MutableStateFlow(
        computeAdaptiveTuning(
            preset = NetworkPreset.BALANCED,
            baseSettings = AppSettings(),
            hardware = readHardwareProfile(),
            signal = readSignalSnapshot()
        )
    )
    val state: StateFlow<AdaptiveTuningResult> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNow()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            refreshNow()
        }

        override fun onLost(network: Network) {
            refreshNow()
        }
    }

    fun startObserving() {
        if (registered) return
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, callback)
            registered = true
            refreshNow()
        }.onFailure { err ->
            AppLogger.warn(TAG, "No se pudo registrar NetworkCallback adaptativo: ${err.message}")
        }
    }

    fun stopObserving() {
        if (!registered) return
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        registered = false
    }

    fun recordLatencySample(latencyMs: Long?, jitterMs: Long? = null) {
        if (latencyMs != null && latencyMs > 0L) {
            lastLatencyMs = latencyMs
        }
        if (jitterMs != null && jitterMs >= 0L) {
            lastJitterMs = jitterMs
        }
        refreshNow()
    }

    fun computeForSettings(settings: AppSettings): AdaptiveTuningResult {
        lastSettings = settings
        lastPreset = settings.networkPreset
        val result = computeAdaptiveTuning(
            preset = settings.networkPreset,
            baseSettings = settings,
            hardware = readHardwareProfile(),
            signal = readSignalSnapshot()
        )
        _state.value = result
        return result
    }

    fun refreshNow(settings: AppSettings? = null) {
        if (settings != null) {
            lastSettings = settings
            lastPreset = settings.networkPreset
        }
        _state.value = computeAdaptiveTuning(
            preset = lastPreset,
            baseSettings = lastSettings,
            hardware = readHardwareProfile(),
            signal = readSignalSnapshot()
        )
    }

    fun readHardwareProfile(): HardwareProfile {
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        val hasMemInfo = runCatching {
            if (am != null) {
                am.getMemoryInfo(memInfo)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        val totalMb = if (hasMemInfo) (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(0L) else 4096L
        val availMb = if (hasMemInfo) (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(0L) else 2048L
        val lowMem = if (hasMemInfo) {
            memInfo.lowMemory || availMb < 512L || totalMb in 1L..2048L
        } else {
            false
        }
        val label = when {
            lowMem || cpuCores <= 4 -> "RAM Limitada (${availMb} MB)"
            else -> "RAM OK"
        }
        return HardwareProfile(
            cpuCores = cpuCores,
            availableRamMb = availMb,
            totalRamMb = totalMb,
            isLowMemory = lowMem,
            ramLabel = label
        )
    }

    fun readSignalSnapshot(): SignalSnapshot {
        val cm = connectivityManager
        val caps = runCatching {
            val active = cm?.activeNetwork
            if (active != null) cm.getNetworkCapabilities(active) else null
        }.getOrNull()

        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

        val transportLabel = when {
            isEthernet -> "Ethernet"
            isWifi -> "Wi-Fi"
            isCellular -> detectCellularGenerationLabel()
            else -> "Red activa"
        }

        val signalBars = extractSignalBars(caps, isWifi, isCellular, isEthernet)
        val tier = when {
            isEthernet -> SignalQualityTier.STRONG
            signalBars <= 0 -> SignalQualityTier.UNKNOWN
            signalBars <= 2 -> SignalQualityTier.WEAK
            signalBars == 3 -> SignalQualityTier.MODERATE
            else -> SignalQualityTier.STRONG
        }

        return SignalSnapshot(
            transportLabel = transportLabel,
            isWifi = isWifi || isEthernet,
            isCellular = isCellular,
            signalBars = signalBars,
            tier = tier,
            recentLatencyMs = lastLatencyMs,
            recentJitterMs = lastJitterMs
        )
    }

    @Suppress("DEPRECATION")
    private fun detectCellularGenerationLabel(): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return "Móvil"
        val networkType = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tm.dataNetworkType
            } else {
                tm.networkType
            }
        }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_CDMA -> "2G"
            else -> "Móvil"
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSignalBars(
        caps: NetworkCapabilities?,
        isWifi: Boolean,
        isCellular: Boolean,
        isEthernet: Boolean
    ): Int {
        if (isEthernet) return 4

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
            val dbm = runCatching { caps.signalStrength }.getOrNull()
            if (dbm != null && dbm != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && dbm < 0) {
                return when {
                    dbm >= -67 -> 4
                    dbm >= -78 -> 3
                    dbm >= -89 -> 2
                    else -> 1
                }
            }
        }

        if (isWifi) {
            val rssi = runCatching {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wm?.connectionInfo?.rssi
            }.getOrNull()
            if (rssi != null && rssi in -127..-1) {
                return when {
                    rssi >= -60 -> 4
                    rssi >= -72 -> 3
                    rssi >= -83 -> 2
                    else -> 1
                }
            }
            val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
            if (downKbps > 0) {
                return when {
                    downKbps >= 50_000 -> 4
                    downKbps >= 15_000 -> 3
                    downKbps >= 3_000 -> 2
                    else -> 1
                }
            }
            return 3
        }

        if (isCellular) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val level = runCatching {
                    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    tm?.signalStrength?.level
                }.getOrNull()
                if (level != null && level in 0..4) {
                    return level.coerceAtLeast(1)
                }
            }
            val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
            if (downKbps > 0) {
                return when {
                    downKbps >= 25_000 -> 4
                    downKbps >= 8_000 -> 3
                    downKbps >= 1_500 -> 2
                    else -> 1
                }
            }
            return 3
        }

        return 3
    }

    companion object {
        private const val TAG = "AdaptiveNetworkTuner"

        fun computeAdaptiveTuning(
            preset: NetworkPreset,
            baseSettings: AppSettings,
            hardware: HardwareProfile,
            signal: SignalSnapshot
        ): AdaptiveTuningResult {
            if (!baseSettings.adaptiveAutoTuningEnabled) {
                val manual = baseSettings.toEngineTuningConfig()
                return AdaptiveTuningResult(
                    effectiveConfig = manual,
                    summaryText = "Manual • ${signal.transportLabel} · Ventana ${manual.sshWindowKb} KB, Búfer ${manual.transferBufferKb} KB, Keep-Alive ${manual.sshKeepAliveSeconds}s",
                    hardware = hardware,
                    signal = signal
                )
            }

            val effectivePlan = if (preset == NetworkPreset.CUSTOM) NetworkPreset.BALANCED else preset
            val planDefaults = baseSettings.applyNetworkPreset(effectivePlan).toEngineTuningConfig()

            val highLatencyOrJitter = (signal.recentJitterMs ?: 0L) >= 60L ||
                (signal.recentLatencyMs ?: 0L) >= 350L
            val isWeakCondition = signal.tier == SignalQualityTier.WEAK || highLatencyOrJitter
            val isStrongCondition = !isWeakCondition && (
                signal.tier == SignalQualityTier.STRONG ||
                    (signal.isWifi && (signal.recentJitterMs ?: 0L) < 35L && (signal.recentLatencyMs ?: 0L) < 160L)
                )

            var windowKb = planDefaults.sshWindowKb
            var bufferKb = planDefaults.transferBufferKb
            var keepAliveSec = planDefaults.sshKeepAliveSeconds
            var queueCap = planDefaults.tunQueueCapacity
            var maxChannels = planDefaults.maxConcurrentSshChannels

            when {
                isWeakCondition -> {
                    windowKb = 128
                    bufferKb = 8
                    keepAliveSec = if ((signal.recentJitterMs ?: 0L) >= 85L || signal.signalBars == 1) 8 else 10
                    queueCap = 48
                    maxChannels = minOf(planDefaults.maxConcurrentSshChannels, 16)
                }
                isStrongCondition -> {
                    when (effectivePlan) {
                        NetworkPreset.MAX_STABILITY -> {
                            windowKb = 256
                            bufferKb = 16
                            keepAliveSec = 12
                            queueCap = 64
                            maxChannels = 20
                        }
                        NetworkPreset.MAX_SPEED -> {
                            windowKb = 512
                            bufferKb = 32
                            keepAliveSec = 20
                            queueCap = 192
                            maxChannels = 32
                        }
                        NetworkPreset.BALANCED,
                        NetworkPreset.MAX_SECURITY_PRIVACY,
                        NetworkPreset.CUSTOM -> {
                            windowKb = 512
                            bufferKb = if (signal.isWifi) 32 else 16
                            keepAliveSec = 15
                            queueCap = 128
                            maxChannels = 28
                        }
                    }
                }
                else -> {
                    // Moderate / Unknown condition
                    when (effectivePlan) {
                        NetworkPreset.MAX_STABILITY -> {
                            windowKb = 128
                            bufferKb = 8
                            keepAliveSec = 10
                            queueCap = 48
                            maxChannels = 16
                        }
                        NetworkPreset.MAX_SPEED -> {
                            windowKb = 256
                            bufferKb = 16
                            keepAliveSec = 15
                            queueCap = 128
                            maxChannels = 28
                        }
                        NetworkPreset.BALANCED,
                        NetworkPreset.MAX_SECURITY_PRIVACY,
                        NetworkPreset.CUSTOM -> {
                            windowKb = 256
                            bufferKb = 16
                            keepAliveSec = 12
                            queueCap = 64
                            maxChannels = 24
                        }
                    }
                }
            }

            // Hardware guardrails for low-RAM or constrained-CPU devices
            val constrainedHardware = hardware.isLowMemory ||
                hardware.availableRamMb < 512L ||
                (hardware.totalRamMb in 1L..2048L) ||
                hardware.cpuCores <= 4

            if (constrainedHardware) {
                bufferKb = minOf(bufferKb, 16)
                queueCap = minOf(queueCap, 64)
                maxChannels = minOf(maxChannels, 16)
                windowKb = minOf(windowKb, 256)
            }

            val config = EngineTuningConfig(
                transferBufferKb = bufferKb,
                immediateFlush = true,
                sshWindowKb = windowKb,
                sshKeepAliveSeconds = keepAliveSec,
                maxConcurrentSshChannels = maxChannels,
                tcpNoDelay = true,
                sshCompression = false,
                tunQueueCapacity = queueCap,
                smartDnsCacheEnabled = true
            )

            val signalLabel = when {
                highLatencyOrJitter -> "Jitter/Latencia alta"
                else -> "Señal ${signal.transportLabel} ${signal.tier.label}"
            }
            val summary = "Activo • $signalLabel • ${hardware.ramLabel} -> " +
                "Ventana ${config.sshWindowKb} KB, Búfer ${config.transferBufferKb} KB, Keep-Alive ${config.sshKeepAliveSeconds}s"

            return AdaptiveTuningResult(
                effectiveConfig = config,
                summaryText = summary,
                hardware = hardware,
                signal = signal
            )
        }
    }
}
