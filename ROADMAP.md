# Roadmap y Estado de Implementación — AIO Tunnel VPN

## Registro de Verificación por Etapas

| Etapa | Alcance | Estado | Notas y Pruebas |
|---|---|---|---|
| **Etapa 0** | Auditoría técnica y matriz de licencias | Completado | Verificado entorno local (`AndroidMegaProxy` ausente). Documentado en `ARCHITECTURE.md` y `LICENSES.md`. |
| **Etapa 1** | Fundación, diseño AMOLED/Gruvbox, Room, Keystore AES-GCM, navegación 5 secciones | Completado y probado | Probado con `AioRobolectricUiTest` y `CryptoAndSecurityTest`. |
| **Etapa 2** | `AioVpnService`, `IpPacketParser`, `TunPacketRouter`, `DnsManager` (DoH/UDP), motores HTTP/HTTPS/SOCKS5/SSH+Jump | Completado y probado (compilación y tests unitarios) | Pruebas reales end-to-end contra servidor remoto requieren dispositivo físico o emulador con acceso de red y credenciales del usuario. |
| **Etapa 3** | Gestor avanzado de perfiles, editor completo, importación/exportación JSON/URI/sing-box/Clash | Completado y probado | Probado con `ConfigParserAndExporterTest`. Exportación excluye secretos por defecto. |
| **Etapa 4** | Adaptadores Fase C (VLESS, VMess, Trojan, SS, WireGuard, Hysteria2, TUIC, NaiveProxy, AnyTLS, REALITY, DNSTT, Slipstream) | Interfaz y parsers listos; binario nativo pendiente | `NativeCoreAdapterEngine` detecta ausencia de `libbox.aar`/`libxray.aar` y rechaza simulación. |
| **Etapa 5** | Diagnósticos reales, latencia TCP/TLS, `NetworkMonitor` con reconexión controlada, suite Robolectric/JUnit | Completado y probado | Suite ejecutada vía `gradle :app:testDebugUnitTest`. |
| **Etapa 6** | Documentación completa y preparación de APK instalable | Completado | Ver instrucciones de compilación APK en `README.md`. |

## Trabajo Pendiente para Futuras Versiones

1. Compilar `libbox.aar` (sing-box) o `libxray.aar` (Xray-core) con Go Mobile (`gomobile bind`) en entorno Linux con Go SDK y Android NDK para activar los protocolos Fase C bajo `NativeCoreAdapterEngine`.
2. Integrar binarios dedicados para `dnstt-client` y `Slipstream` cumpliendo sus respectivas licencias (AGPL/MPL) y documentando los requisitos de servidor autoritativo.
3. Añadir pruebas de integración instrumentadas/end-to-end contra un servidor de pruebas controlado (SSH + Squid/3proxy + Dante) fuera del entorno headless sin ADB.
