# Wabridge

**Notas de voz de WhatsApp como llamadas en el reloj.** Wabridge envía las notas de voz entrantes de WhatsApp a un teléfono o reloj Huawei como llamadas telefónicas y devuelve la respuesta grabada como nota de voz.

[English](README.md) · [Português](README.pt-BR.md) · [Español](README.es.md) · [简体中文](README.zh-CN.md)

## Cómo funciona

```text
WhatsApp / Evolution API
        │ webhook de nota de voz entrante
        ▼
Servidor Node.js o motor Go integrado
        │ prepara el audio y envía un evento WebSocket
        ▼
App Android ── Bluetooth HFP/SCO ── reloj
        ▲                                  │
        └──── respuesta de voz grabada ────┘
```

La app Android admite un motor Go integrado o un servidor Node.js externo. El audio entrante se presenta como una llamada; al contestar, el reloj reproduce la nota y graba la respuesta. La app envía la grabación original al motor, que la entrega como nota de voz de WhatsApp.

## Estructura del repositorio

| Ruta | Función |
|---|---|
| `android-bridge/` | App Android, código Kotlin, build Gradle y pruebas JVM |
| `embedded-core/` | Motor de WhatsApp en Go, API HTTP/WebSocket y pruebas |
| `server/` | Servidor opcional Node.js/TypeScript integrado con Evolution API |
| `.github/workflows/ci.yml` | Pruebas y builds/releases del APK en CI |

## Descargar la app Android

Descarga el APK firmado desde [GitHub Releases](https://github.com/KelvinKSPS/wabridge/releases). El workflow de release se ejecuta al subir una etiqueta `v*`. CI también genera artefactos APK de debug en pushes y pull requests.

## Build y pruebas

GitHub Actions compila los APK de release. Para ejecutar localmente las pruebas JVM:

```bash
cd android-bridge
./gradlew testDebugUnitTest --console=plain
```

Para probar el motor Go integrado:

```bash
cd embedded-core
go test ./...
```

Para el servidor Node opcional:

```bash
cd server
npm ci
npm test
npm run build
```

La app Android requiere JDK 17 y la configuración del Android SDK. El workflow de CI compila bibliotecas Go para Android `arm64-v8a` y `x86_64`, empaqueta el APK y publica los artefactos. Una release firmada requiere los secrets de firma Android configurados en el repositorio; sin ellos, el workflow indica que el keystore no está disponible.

## Configuración

1. Instala el APK en el teléfono o reloj Android.
2. Vincula la cuenta de WhatsApp mediante el flujo de emparejamiento de la app.
3. Elige el motor integrado o configura la URL de un servidor externo.
4. Concede los permisos de micrófono, Bluetooth y notificaciones cuando se soliciten.
5. Opcionalmente, selecciona dispositivos Bluetooth y contactos/grupos permitidos; después, inicia el servicio.

Para el servidor externo, configura `.env` a partir de `server/.env.example`, incluidas las credenciales de Evolution API y el host del webhook. No incluyas secrets en el repositorio. Consulta [`server/README.md`](server/README.md) para las rutas y la configuración del servidor.

## Funciones

- Servicio Android en primer plano con reconexión del WebSocket de WhatsApp.
- Reproducción y grabación de respuestas mediante Bluetooth HFP/SCO.
- Velocidad de reproducción configurable, incluidos valores personalizados de 0,25× a 4×.
- Retardo configurable antes de reproducir el audio.
- Historial de audios recibidos, atendidos y rechazados, con opción para borrarlo.
- Filtro opcional por dispositivos Bluetooth y lista permitida de contactos/grupos.
- Diagnóstico de audio y conservación de la grabación original para investigar problemas.
- App localizada en portugués, inglés, español y chino simplificado.

## Seguridad y privacidad

La app procesa las credenciales de WhatsApp y el audio localmente o mediante el servidor configurado. Usa una red de confianza, protege las credenciales de Evolution API y las claves de firma, y no publiques diagnósticos ni registros con datos personales. El micrófono solo se necesita al grabar la respuesta; el indicador de privacidad de Android permanece visible mientras se usa el micrófono.

## Licencia

Consulta el archivo de licencia del repositorio, si existe.
