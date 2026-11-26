package com.example.domain.models

data class TunnelStatistics(
    val bytesReceived: Long = 0L,
    val bytesSent: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val activeTcpConnections: Int = 0,
    val totalPacketsForwarded: Long = 0L,
    val packetsPerSec: Long = 0L,
    val dnsQueriesResolved: Long = 0L,
    val sessionDurationSeconds: Long = 0L
)
