# Handoff — chave de assinatura (PK) e APK release

Objetivo: gerar o keystore do app e producir o APK assinado, para instalar
no relógio sem perder a chave em builds futuros.

## Onde

`~/Desktop/Projects/wabridge/android-bridge`

## Passos

```bash
cd ~/Desktop/Projects/wabridge/android-bridge

# 1. gera o .jks e escreve keystore.properties
bash scripts/make-keystore.sh <SENHA>          # ou: bash scripts/make-keystore.sh <SENHA> /caminho/wabridge.jks

# 2. build do APK release
export ANDROID_HOME=/home/orangepi/android-sdk
./gradlew assembleRelease --console=plain

# 3. saída
ls -l app/build/outputs/apk/release/app-release.apk
```

O `app/build.gradle.kts` já lê `keystore.properties` e aplica a assinatura
sozinho. Sem esse arquivo o release sai assinado com a chave de **debug**.

## Requisitos

- JDK 17 (`/usr/lib/jvm/java-17-openjdk-arm64`).
- Android SDK com platform 35 + build-tools 35 (`ANDROID_HOME=/home/orangepi/android-sdk`).
- Gradle wrapper incluso (8.10.2) — não precisa instalar nada.
- Rede só se faltar dependência (o cache em `~/.gradle` já está quente).

## Entregáveis

1. `app/build/outputs/apk/release/app-release.apk` (instalar no relógio).
2. `wabridge.jks` + `keystore.properties` — **guardar**: sem a mesma chave o
   relógio não aceita atualização do app (exige desinstalar e perder os dados).

## Depois de instalar

O app abre a tela inicial: campo com a URL do servidor (`http://IP:8097`) e
botão **Conectar**. Ele pede `RECORD_AUDIO` (e `BLUETOOTH_CONNECT` no
Android 12+). O relógio precisa estar na mesma rede do servidor.
