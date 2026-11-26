# Changelog — AIO Tunnel VPN

## [1.0.0] - 2026-11-25
### Añadido
- Arquitectura modular Kotlin + Jetpack Compose + Material 3 (AMOLED oscuro inspirado en Gruvbox y tema claro cálido opcional).
- Navegación inferior/lateral adaptativa de 5 secciones: Inicio, Servidores, Perfiles, Diagnóstico y Ajustes.
- Servicio VPN real (`AioVpnService`) con interfaz TUN, soporte IPv4/IPv6, Split Tunneling por aplicación, reconexión ante cambios de red y prevención de bucles recursivos.
- Router TUN en espacio de usuario (`TunPacketRouter` + `IpPacketParser`) con soporte real TCP/UDP y DNS-over-HTTPS (RFC 8484) / DNS UDP protegido.
- Motores integrados Fase A: HTTP CONNECT, HTTPS CONNECT (verificación TLS X.509/SNI), SOCKS5 (TCP + UDP Associate) y SSH2 (`mwiede/jsch` con contraseña, clave privada PEM, Jump Host y verificación interactiva SHA-256 de host keys).
- Contrato Fase C (`NativeCoreAdapterEngine`) para futura vinculación de `sing-box` / `Xray-core` / `Slipstream` sin simulaciones.
- Bóveda de secretos cifrada con Android Keystore (`AES-256-GCM`), sanitización automática de logs y exportaciones sin credenciales por defecto.
- Suite de pruebas unitarias y Robolectric (`ConfigParserAndExporterTest`, `CryptoAndSecurityTest`, `IpPacketParserAndEngineTest`, `AioRobolectricUiTest`).
