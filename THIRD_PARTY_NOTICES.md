# Avisos de Terceros — AIO Tunnel VPN

AIO Tunnel VPN no incluye ni redistribuye binarios o código fuente de proyectos GPL/AGPL (como Hiddify, SlipNet o sing-box). A continuación se inventarian las dependencias reales integradas en la compilación del APK:

| Dependencia | Versión | Autor / Mantenedor | Licencia | Uso en AIO Tunnel VPN |
|---|---|---|---|---|
| `com.github.mwiede:jsch` | 2.28.8 | Matthias Wiedemann (fork de JCraft JSch) | BSD-3-Clause | Cliente SSH2 real, claves privadas, port forwarding Jump Host y verificación de host keys. |
| `com.squareup.okhttp3:okhttp` | 4.10.0 | Square, Inc. | Apache-2.0 | Consultas DNS-over-HTTPS (RFC 8484) sobre sockets protegidos. |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.13.0 | JetBrains s.r.o. | Apache-2.0 | Serialización tipada de rutas Compose y formato JSON AIO v1. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-*` | 1.10.2 | JetBrains s.r.o. | Apache-2.0 | Concurrencia asíncrona y manejo de flujos TUN fuera del hilo principal. |
| `androidx.room:*` | 2.7.0 | The Android Open Source Project | Apache-2.0 | Persistencia local de perfiles de servidor (sin credenciales en claro). |
| `androidx.datastore:datastore-preferences` | 1.1.7 | The Android Open Source Project | Apache-2.0 | Almacenamiento de preferencias de usuario. |
| `androidx.compose.*` / `material3` / `navigation-compose` | BOM 2024.09.00 / 2.8.9 | The Android Open Source Project | Apache-2.0 | Interfaz de usuario Material 3 Jetpack Compose. |
| Fuentes locales (`Space Grotesk`, `Plus Jakarta Sans`, `JetBrains Mono`) | Bundled TTF | Google Fonts / Autores respectivos | SIL Open Font License 1.1 | Tipografía local incluida en `res/font/`. |
| `org.robolectric:robolectric` | 4.16.1 | Robolectric Contributors | MIT | Pruebas automatizadas locales en JVM (solo `testImplementation`). |
| `junit:junit` | 4.13.2 | JUnit Team | EPL-1.0 | Ejecución de pruebas unitarias (solo `testImplementation`). |

## Proyectos de Referencia Auditados (No incrustados en este APK)

- **AndroidMegaProxy** (`andre487/AndroidMegaProxy`, MIT) — Consultado conceptualmente como referencia de arquitectura VPN/proxy.
- **Xray-core** (`XTLS/Xray-core`, MPL-2.0) — Evaluado para integración futura vía `NativeCoreAdapterEngine`.
- **sing-box** (`SagerNet/sing-box`, GPLv3+) — Evaluado como núcleo multiprotocolo opcional sujeto a cumplimiento GPLv3+.
- **Hiddify** (`hiddify/hiddify-app`, GPLv3 + condiciones Sección 7) y **SlipNet** (`anonvector/SlipNet`, AGPL-3.0) — Auditados por licencia y excluidos de copia directa.
