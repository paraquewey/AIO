# AIO Tunnel VPN

Cliente VPN Android multiprotocolo nativo desarrollado con Kotlin, Jetpack Compose, Material 3, Android `VpnService`, Room, DataStore y Android Keystore.

## Capacidades actuales (Motores integrados reales)

- **HTTP Proxy (HTTP CONNECT)** con encabezados personalizados permitidos y autenticación Basic.
- **HTTPS Proxy (TLS + HTTP CONNECT)** con validación estricta de certificados X.509, vigencia y nombre de dominio.
- **SOCKS5 (RFC 1928 / RFC 1929)** con soporte TCP CONNECT y UDP ASSOCIATE.
- **SSH Tunnel (JSch)** con contraseña, clave privada OpenSSH/PEM, **SSH Jump Host** multi-salto y verificación interactiva de huellas SHA-256 (`SshHostKeyStore`).
- **DNS convencional UDP y DNS-over-HTTPS (RFC 8484)** sobre sockets protegidos (`VpnService.protect()`).
- **Split Tunneling por aplicación**, soporte IPv4/IPv6 opcional, reconexión ante cambios de red y protección contra bucles recursivos.
- **Gestor de perfiles**: JSON AIO v1, URIs (`ssh://`, `socks5://`, `http://`, `https://`, `vless://`, `vmess://`, `trojan://`, `ss://`), subconjuntos sing-box y Clash YAML. Las contraseñas y claves no se exportan por defecto.

> **Nota sobre Fase C (Núcleo Nativo Go/C++):** Los protocolos VLESS, VMess, Trojan, Shadowsocks, WireGuard, Hysteria2, TUIC, NaiveProxy, AnyTLS, REALITY, DNSTT y Slipstream cuentan con modelos, parsers e interfaz `TunnelEngine` (`NativeCoreAdapterEngine`), pero **no se simulan**. Requieren compilar e incluir `libbox.aar` o `libxray.aar` mediante Go mobile / NDK (ver `ROADMAP.md` y `LICENSES.md`).

## Cómo compilar y generar el APK

### Desde línea de comandos
```bash
# Compilar APK de depuración
gradle :app:assembleDebug

# Ejecutar pruebas unitarias y Robolectric
gradle :app:testDebugUnitTest
```
El APK generado se ubicará en `app/build/outputs/apk/debug/app-debug.apk`.

### Desde Android Studio
1. Abre la carpeta raíz del proyecto en Android Studio (Ladybug / Meerkat o superior con JDK 17+).
2. Sincroniza Gradle (`Sync Project with Gradle Files`).
3. Selecciona un dispositivo físico o emulador con API 26+ (Android 8.0+).
4. Pulsa **Run 'app'** (`Shift + F10`).
5. Para generar un APK instalable firmado, usa **Build > Generate Signed App Bundle / APK**.

## Documentación del proyecto

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — Auditoría Etapa 0, arquitectura modular y flujo TUN.
- [`ROADMAP.md`](ROADMAP.md) — Estado real por etapas (implementado, probado y pendiente).
- [`SECURITY.md`](SECURITY.md) — Controles criptográficos, TLS, SSH host keys y sanitización.
- [`PRIVACY.md`](PRIVACY.md) — Política de cero telemetría y transparencia de metadatos.
- [`LICENSES.md`](LICENSES.md) — Matriz de compatibilidad de licencias open source.
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — Inventario de dependencias y atribuciones.
- [`CHANGELOG.md`](CHANGELOG.md) — Historial de versiones.
