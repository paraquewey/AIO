# Arquitectura de AIO Tunnel VPN

## Etapa 0 — Auditoría Técnica y Verificación del Entorno

1. **Verificación de repositorio `AndroidMegaProxy`:**
   - Al inspeccionar el espacio de trabajo local (`/`), no se encontró una copia clonada de `andre487/AndroidMegaProxy` ni de repositorios externos.
   - De acuerdo con la metodología, AIO Tunnel VPN se implementó desde cero con arquitectura nativa modular en Kotlin sobre las interfaces oficiales de Android (`VpnService`, `ConnectivityManager`, `AndroidKeyStore`, `Room`, `DataStore`).
2. **Evaluación de referencia y compatibilidad:**
   - **AndroidMegaProxy (MIT):** Referencia arquitectónica compatible para separación de motores, Split Tunneling, SSH Jump Hosts y Keystore AES-GCM.
   - **Xray-core (MPL-2.0):** Compatible mediante librería compilada separada (obligación file-level MPL-2.0 sobre archivos modificados).
   - **sing-box (GPLv3+ con cláusula de marca):** Incompatible con distribución propietaria cerrada o reutilización de marca sin consentimiento; aislado tras `NativeCoreAdapterEngine`.
   - **Hiddify (GPLv3 + condiciones adicionales Sección 7)** y **SlipNet (AGPL-3.0):** No incorporados en el código base para preservar compatibilidad de licenciamiento e identidad propia.

## Estructura Modular

```
com.example
├── core/
│   ├── connection/ConnectionManager.kt   # Orquestación de servicio VPN y desafíos SSH
│   ├── logger/AppLogger.kt               # Ring buffer sanitizado (sin secretos)
│   └── stats/StatisticsManager.kt        # Contadores atómicos reales RX/TX/paquetes/DNS
├── domain/
│   ├── models/                           # TunnelProfile, TunnelProtocol, ConnectionState, AppSettings
│   └── repositories/                     # ProfileRepository, SettingsRepository
├── data/
│   ├── database/                         # Room DAO y Entity (sin secretos en claro)
│   ├── preferences/                      # Jetpack DataStore Preferences
│   ├── import_export/                    # Parsers JSON v1, URI, sing-box, Clash + exportador seguro
│   └── security/                         # KeystoreCryptoManager (AES-256-GCM) y SshHostKeyStore
├── engines/
│   ├── adapter/TunnelEngine.kt           # Interfaz desacoplada común para todos los motores
│   ├── http/HttpConnectProxyEngine.kt    # HTTP y HTTPS CONNECT con validación X.509/SNI
│   ├── socks/Socks5TunnelEngine.kt       # SOCKS5 RFC 1928/1929 (TCP + UDP Associate)
│   ├── ssh/SshTunnelEngine.kt            # JSch SSH2 (password, PEM, Jump Host, host key check)
│   └── nativecore/NativeCoreAdapterEngine.kt # Contrato Fase C (sing-box/Xray/Slipstream)
├── vpn/
│   ├── service/AioVpnService.kt          # VpnService foreground, Builder TUN, Split Tunneling, onRevoke
│   ├── tun/IpPacketParser.kt             # Codec IPv4 / TCP / UDP y checksums RFC
│   ├── tun/DnsManager.kt                 # DNS UDP protegido y DoH RFC 8484
│   ├── tun/TunPacketRouter.kt            # Router TUN en espacio de usuario -> TunnelEngine
│   └── monitor/NetworkMonitor.kt         # Detección de cambio de red (NET_CAPABILITY_NOT_VPN)
└── presentation/                         # Compose M3 (AMOLED + Gruvbox), ViewModel y 5 pantallas
```

## Flujo de Conexión y Prevención de Bucles

1. La UI solicita `VpnService.prepare(context)` si aún no existe consentimiento del sistema.
2. `AioVpnService` inicia en primer plano (`foregroundServiceType="specialUse"`), instancia el `TunnelEngine` correspondiente y ejecuta `engine.start(...)` **protegiendo cada socket saliente** con `VpnService.protect(socket)`.
3. Solo tras autenticar el motor remoto se llama a `VpnService.Builder.establish()`, excluyendo explícitamente el propio paquete (`addDisallowedApplication(packageName)`) para impedir enrutamiento recursivo.
4. `TunPacketRouter` lee paquetes IPv4 del descriptor TUN, resuelve DNS puerto 53 vía `DnsManager` (DoH o UDP protegido) y puentea flujos TCP/UDP hacia `engine.openTcpTunnel(...)` / `engine.openUdpRelay()`.
