package com.example.engines.adapter

import com.example.data.security.SshHostKeyStore
import com.example.domain.models.EngineFamily
import com.example.domain.models.TunnelProtocol
import com.example.engines.http.HttpConnectProxyEngine
import com.example.engines.nativecore.NativeCoreAdapterEngine
import com.example.engines.socks.Socks5TunnelEngine
import com.example.engines.ssh.SshTunnelEngine

class EngineFactory(
    private val hostKeyStore: SshHostKeyStore
) {
    fun createForProtocol(protocol: TunnelProtocol): TunnelEngine = when (protocol.engineFamily) {
        EngineFamily.HTTP_CONNECT -> HttpConnectProxyEngine()
        EngineFamily.SOCKS5 -> Socks5TunnelEngine()
        EngineFamily.SSH -> SshTunnelEngine(hostKeyStore)
        EngineFamily.NATIVE_CORE -> NativeCoreAdapterEngine()
    }
}
