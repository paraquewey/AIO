# Changelog — AIO Tunnel VPN

## [1.4.0] - 2026-11-26
### Auto-Replace HTTP 100–599, Picos de Velocidad, Geo-IP y Nuevos Ajustes
- **Soporte Universal de Respuestas HTTP (100–599 Auto-Replace):**
  - `RawTcpPayloadExecutor` ahora acepta cualquier respuesta válida `HTTP/1.0` o `HTTP/1.1` en rango `100..599` (`503`, `502`, `405`, `400`, `403`, `301/302`, etc.), drena únicamente las cabeceras hasta `\r\n\r\n` preservando intacto el banner `SSH-2.0-...`, soporta múltiples fragmentos `[split]` y registra `"HTTP/1.1 <CÓDIGO> recibido -> Auto-Replace a 200 OK"`.
- **Pantalla Inicio (Picos de velocidad e IP de salida con bandera):**
  - `StatisticsManager` y `DashboardScreen` ahora calculan y muestran el **Pico máximo alcanzado** en la sesión actual tanto para Descarga como para Subida, reiniciándose automáticamente en cada nueva conexión.
  - `DnsManager` consulta de forma asíncrona a través del túnel (`ip-api.com` / `ipwho.is`) la IP pública de salida junto con bandera emoji por código de país, país y ciudad (ej. `🇦🇷 Argentina • Buenos Aires`).
- **Nuevas opciones en Ajustes (`DataStore` + Servicios):**
  - **Seguir intentando conectarse** (activado por defecto) en `ConnectionManager` con reintento automático cada 2.5 s ante fallos o caídas inesperadas.
  - **Tiempo de espera (Timeout)** editable y validado de `1` a `999` segundos (por defecto `10` s), aplicado dinámicamente a `socket.connect` y `socket.soTimeout` en todos los motores.
  - **Compartir VPN por Wi-Fi / Hotspot (`0.0.0.0:8080`)**: servidor local `WifiShareProxyServer` (HTTP `CONNECT` / forward y SOCKS5) enrutado por el túnel activo, con detección automática de IP local y botón de copiar.
  - **Ejecución en segundo plano (Optimización de batería)**: indicador visual de estado (`PowerManager.isIgnoringBatteryOptimizations`) e intent directo de Android con fallback compatible por fabricante.

## [1.3.0] - 2026-11-26
### Enrutamiento SSH Real (`direct-tcpip`), SOCKS5 Local, BadVPN UDPGW y Pila TCP/IP en TUN
- **Puente TCP/IP -> SSH (`TunPacketRouter` + `SshTunnelEngine`):**
  - Pila TCP en espacio de usuario refactorizada en `TunPacketRouter`: deduplicación de `SYN` retransmitidos sin abortar el canal `direct-tcpip`, opción MSS en `SYN-ACK`, cola ordenada por flujo (`Channel`) con buffer de reensamblado fuera de orden (`TreeMap`) y cierre medio (`halfCloseOutput`) al recibir `FIN` del cliente para conservar respuestas remotas.
  - Nuevo servidor local **SOCKS5 RFC 1928** (`LocalSocks5Server` en `127.0.0.1`) respaldado por canales `direct-tcpip` de `SshTunnelEngine`, con registro explícito `"SSH Port Forwarding (direct-tcpip / SOCKS5 127.0.0.1:<puerto>) listo"`.
- **BadVPN UDPGW y DNS Forwarding activo:**
  - Nuevo cliente `BadVpnUdpgwClient` sobre `direct-tcpip` hacia `127.0.0.1:<udpgwPort>` (puerto configurable en Editar Perfil, por defecto `7300`; `0` para desactivar), con soporte completo de frames little-endian/big-endian, keepalive, `FLAG_DNS` y multiplexación bidireccional de flujos UDP hacia `tun0`.
  - Interceptor DNS activo incluso en modo `SYSTEM_DEFAULT` (vía BadVPN UDPGW o `DNS-over-TCP` RFC 1035 sobre `direct-tcpip`) sin evadir el túnel por UDP directo, con registro `"DNS Forwarding activo sobre túnel ..."` y `"UDPGW conectado al puerto remoto <puerto>"`.
- **Telemetría y Base de Datos:**
  - Agregada métrica en tiempo real de paquetes por segundo (`packetsPerSec`) en `StatisticsManager` y `DashboardScreen`.
  - Migración Room v3 -> v4 (`MIGRATION_3_4`) añadiendo `udpgwPort` a `tunnel_profiles`.

## [1.2.0] - 2026-11-26
### Refactorización de Payload Crudo y Sockets TCP/TLS
- Reemplazados los selectores rígidos de método/ruta/encabezados en **Editar perfil** por un campo multilínea sin restricciones **"Payload HTTP / WebSocket (Crudo)"**.
- Eliminada por completo la validación restrictiva `"Encabezado HTTP inválido (CRLF o espacio en clave detectado)"`.
- Nuevo motor parser `RawPayloadParser` y ejecutor de socket crudo `RawTcpPayloadExecutor` (`Socket` / `SSLSocket`) con soporte completo para `[crlf]`, `[lf]`, `[cr]`, `[lfcr]`, `[host]`, `[port]`, `[host_port]`, `[ip]`, `[ssh]`, `[protocol]`, `[method]`, `[netData]`, `[raw]`, `[real_raw]`, `[auth]`, `[ua]`, `[rotate=...]`, `[random=...]`, y fragmentación TCP (`[split]`, `[instant_split]`, `[splitNoDelay]`, `[delay_split]`, `[split_delay=X]`).
- Integrado el handshake de payload crudo tanto en `HttpConnectProxyEngine` como en `SshTunnelEngine` (para túneles SSH sobre HTTP/WebSocket o TLS directo), manteniendo conexión directa sin inyección cuando el payload está vacío.
- Migración de base de datos Room v2 -> v3 (`MIGRATION_2_3`) añadiendo `rawHttpPayload`.

## [1.1.0] - 2026-11-26
### Mejorado y Corregido
- Soporte dinámico de **Servidor DNS 1 (Primario)** y **Servidor DNS 2 (Secundario)** en Editar Perfil, Ajustes y `AioVpnService` (con herencia automática de Ajustes y respaldo automático DNS 1 -> DNS 2).
- Enrutamiento DNS obligatorio **por dentro del túnel activo** mediante **DNS-over-HTTPS** sobre túnel TLS o **DNS-over-TCP** (prefijo de 2 bytes RFC 1035), eliminando el uso de UDP directo/protegido para consultas de usuario.
- Timeout estricto por consulta DNS (2500 ms), cancelación limpia al detener la VPN y limitación de frecuencia en `AppLogger` para evitar saturación de registros.
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
