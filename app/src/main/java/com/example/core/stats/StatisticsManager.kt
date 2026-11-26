package com.example.core.stats

import com.example.domain.models.TunnelStatistics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StatisticsManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    private val rxBytes = AtomicLong(0L)
    private val txBytes = AtomicLong(0L)
    private val totalPackets = AtomicLong(0L)
    private val dnsQueries = AtomicLong(0L)
    private val activeConnections = AtomicInteger(0)

    private var sessionStartMillis: Long = 0L
    private var prevRx: Long = 0L
    private var prevTx: Long = 0L

    private val _stats = MutableStateFlow(TunnelStatistics())
    val stats: StateFlow<TunnelStatistics> = _stats.asStateFlow()

    fun startSession() {
        resetCounters()
        sessionStartMillis = System.currentTimeMillis()
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                publishSnapshot()
            }
        }
    }

    fun stopSession() {
        tickerJob?.cancel()
        tickerJob = null
        publishSnapshot(finalZeroRates = true)
    }

    fun resetCounters() {
        rxBytes.set(0L)
        txBytes.set(0L)
        totalPackets.set(0L)
        dnsQueries.set(0L)
        activeConnections.set(0)
        prevRx = 0L
        prevTx = 0L
        sessionStartMillis = 0L
        _stats.value = TunnelStatistics()
    }

    fun recordBytesReceived(bytes: Long) {
        if (bytes > 0) rxBytes.addAndGet(bytes)
    }

    fun recordBytesSent(bytes: Long) {
        if (bytes > 0) txBytes.addAndGet(bytes)
    }

    fun recordPacketForwarded() {
        totalPackets.incrementAndGet()
    }

    fun recordDnsQuery() {
        dnsQueries.incrementAndGet()
    }

    fun setActiveTcpConnections(count: Int) {
        activeConnections.set(count.coerceAtLeast(0))
    }

    private fun publishSnapshot(finalZeroRates: Boolean = false) {
        val currentRx = rxBytes.get()
        val currentTx = txBytes.get()
        val dlRate = if (finalZeroRates) 0L else (currentRx - prevRx).coerceAtLeast(0L)
        val ulRate = if (finalZeroRates) 0L else (currentTx - prevTx).coerceAtLeast(0L)
        prevRx = currentRx
        prevTx = currentTx
        val elapsedSec = if (sessionStartMillis > 0L) {
            ((System.currentTimeMillis() - sessionStartMillis) / 1000L).coerceAtLeast(0L)
        } else 0L

        _stats.value = TunnelStatistics(
            bytesReceived = currentRx,
            bytesSent = currentTx,
            downloadBytesPerSec = dlRate,
            uploadBytesPerSec = ulRate,
            activeTcpConnections = activeConnections.get(),
            totalPacketsForwarded = totalPackets.get(),
            dnsQueriesResolved = dnsQueries.get(),
            sessionDurationSeconds = elapsedSec
        )
    }
}
