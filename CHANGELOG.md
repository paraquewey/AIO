# Changelog — AIO Tunnel VPN

## [1.1.0] - 2026-11-26
### Mejorado y Corregido
- Soporte dinámico de **Servidor DNS 1 (Primario)** y **Servidor DNS 2 (Secundario)** en Editar Perfil, Ajustes y `AioVpnService` (con herencia automática de Ajustes y respaldo automático DNS 1 -> DNS 2).
- Enrutamiento DNS obligatorio **por dentro del túnel activo** mediante **DNS-over-HTTPS** sobre túnel TLS o **DNS-over-TCP** (prefijo de 2 bytes RFC 1035), eliminando el uso de UDP directo/protegido para consultas de usuario.
- Timeout estricto por consulta DNS (2500 ms), cancelación limpia al detener la VPN y limitación de frecuencia en `AppLogger` para evitar saturación de registros.
- Soporte completo en `HttpConnectProxyEngine` para métodos HTTP (`CONNECT`, `GET`, `HEAD`, `POST`, `PUT`) y encabezados `Upgrade: websocket` (`101 Switching Protocols`).
- Diagnóstico separado según estado de la VPN: pruebas DNS/DoH por dentro del túnel cuando está conectado, y validación de endpoint respetando `tlsEnabled` cuando está desconectado.

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
