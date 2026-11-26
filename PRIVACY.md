# Privacidad — AIO Tunnel VPN

## Principios de Privacidad

- **Cero telemetría no consentida:** AIO Tunnel VPN no envía analíticas, métricas de uso, diagnósticos remotos ni identificadores del dispositivo a ningún servidor propio ni de terceros.
- **Sin publicidad ni rastreadores:** El proyecto no integra SDKs de anuncios, Firebase Analytics ni rastreadores.
- **Sin servidores ocultos:** La aplicación no contiene listas ocultas de servidores ni descarga configuraciones remotas no autorizadas por el usuario. Toda conexión se realiza exclusivamente hacia los servidores configurados o importados por el propio usuario.
- **Almacenamiento local cifrado:** Los perfiles se almacenan localmente en Room y sus secretos en la bóveda cifrada con Android Keystore (AES-256-GCM).

## Transparencia sobre Metadatos de Red

Al conectarte mediante un servidor proxy, SSH o VPN externo:
- El **proveedor u operador del servidor destino** al que te conectes puede observar metadatos de la conexión, incluyendo tu dirección IP pública de origen, marca temporal de sesión, volumen de datos transferidos y destinos de conexión saliente (salvo cifrado adicional extremo a extremo como HTTPS/TLS entre tu dispositivo y el sitio final).
- Si utilizas DNS-over-HTTPS (DoH), las consultas DNS son visibles para el proveedor DoH configurado (por ejemplo, `cloudflare-dns.com`).
