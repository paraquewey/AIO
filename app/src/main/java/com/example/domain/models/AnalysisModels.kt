package com.example.domain.models

import com.example.vpn.tun.DnsManager

data class SpeedSample(
    val elapsedSec: Float,
    val downloadMbps: Float,
    val uploadMbps: Float = 0f
)

data class SpeedTestState(
    val isRunning: Boolean = false,
    val phaseLabel: String = "Listo para medir velocidad real",
    val downloadMbps: Float? = null,
    val uploadMbps: Float? = null,
    val peakDownloadMbps: Float? = null,
    val timeToPeakSec: Float? = null,
    val samples: List<SpeedSample> = emptyList(),
    val usedTunnel: Boolean = false,
    val errorMessage: String? = null
)

data class LatencyStabilityState(
    val isRunning: Boolean = false,
    val pingSamplesMs: List<Long?> = emptyList(),
    val minMs: Long? = null,
    val avgMs: Long? = null,
    val maxMs: Long? = null,
    val jitterMs: Long? = null,
    val packetLossPercent: Float? = null,
    val qualityGrade: String? = null,
    val usedTunnel: Boolean = false,
    val errorMessage: String? = null
)

data class PortProbeEntry(
    val port: Int,
    val isOpen: Boolean,
    val httpCode: Int? = null,
    val tlsOk: Boolean? = null,
    val latencyMs: Long? = null,
    val note: String = ""
)

data class PortScannerState(
    val isRunning: Boolean = false,
    val targetHost: String = "",
    val results: List<PortProbeEntry> = emptyList(),
    val errorMessage: String? = null
)

data class DnsLeakReport(
    val isRunning: Boolean = false,
    val activeModeLabel: String = "",
    val primaryResolver: String = "",
    val secondaryResolver: String = "",
    val dohStatus: String = "",
    val leakStatus: String = "",
    val exitIpInfo: DnsManager.ExitIpInfo? = null,
    val usedTunnel: Boolean = false,
    val errorMessage: String? = null
)
