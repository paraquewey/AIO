package com.example.vpn.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.core.logger.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(
    context: Context,
    private val onUnderlyingNetworkChanged: () -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _hasInternetCapability = MutableStateFlow(true)
    val hasInternetCapability: StateFlow<Boolean> = _hasInternetCapability.asStateFlow()

    private var registered = false
    private var lastNetworkId: String? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val id = network.toString()
            _hasInternetCapability.value = true
            if (lastNetworkId != null && lastNetworkId != id) {
                AppLogger.info(TAG, "Cambio de red subyacente detectado ($lastNetworkId -> $id)")
                onUnderlyingNetworkChanged()
            }
            lastNetworkId = id
        }

        override fun onLost(network: Network) {
            AppLogger.warn(TAG, "Red subyacente perdida: $network")
            _hasInternetCapability.value = false
        }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            registered = true
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        registered = false
        lastNetworkId = null
    }

    companion object {
        private const val TAG = "NetworkMonitor"
    }
}
