# Seguridad — AIO Tunnel VPN

## Controles Implementados

1. **Cifrado de credenciales en reposo (Android Keystore + AES-256-GCM):**
   - Las contraseñas, claves privadas PEM y passphrases nunca se guardan en Room ni en `SharedPreferences` en texto plano.
   - `KeystoreCryptoManager` genera una clave AES-256 en `AndroidKeyStore` y cifra cada bóveda por perfil con `AES/GCM/NoPadding` e IV aleatorio por operación.
   - Al eliminar un perfil, el archivo cifrado se sobrescribe con bytes aleatorios antes de eliminarse.
2. **Validación estricta TLS / HTTPS:**
   - `HttpConnectProxyEngine` verifica la cadena de confianza X.509 predeterminada del sistema, la vigencia del certificado y el nombre de dominio (`HttpsURLConnection.getDefaultHostnameVerifier()`).
   - La verificación TLS está activada por defecto y requiere confirmación explícita del usuario en el editor avanzado para desactivarse.
3. **Verificación SSH de Host Keys (TOFU + Alerta MITM):**
   - `SshHostKeyStore` calcula huellas SHA-256 de la clave pública del servidor SSH.
   - Ante un host desconocido o un cambio de clave, `SshTunnelEngine` detiene el handshake y solicita confirmación explícita al usuario mediante `SshHostKeyDialog`.
4. **Prevención de bucles de enrutamiento recursivo:**
   - Todos los sockets de red del túnel (`HttpConnectProxyEngine`, `Socks5TunnelEngine`, `SshTunnelEngine` y `DnsManager`) pasan obligatoriamente por `VpnService.protect()`.
   - `AioVpnService` excluye además el paquete propio mediante `addDisallowedApplication(packageName)`.
5. **Sanitización de registros y exportaciones:**
   - `AppLogger` redacta contraseñas, tokens Bearer, claves privadas PEM y credenciales en URI antes de almacenar cualquier entrada.
   - Las exportaciones JSON y URI excluyen secretos por defecto.
6. **Validación de archivos importados:**
   - `ConfigParserAndExporter` limita el tamaño de entrada a 2 MB, valida rango de puertos (1-65535), formato de host/MTU y rechaza inyecciones CRLF en encabezados HTTP.
