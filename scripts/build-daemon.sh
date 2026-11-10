#!/usr/bin/env bash
# Compila o daemon Go (motor do WhatsApp) para Android.
#
# O daemon usa o mattn/go-sqlite3 (CGO), então precisa do NDK: o sqlite puro
# em Go (modernc.org/sqlite) faz syscalls que o seccomp do Android bloqueia.
#
# Uso: scripts/build-daemon.sh [arm64|x86_64|tudo]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../embedded-core"
APP="../android-bridge/app/src/main/jniLibs"

if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -d "$HOME/android-sdk/ndk" ]; then
    export ANDROID_NDK_HOME="$(ls -d "$HOME"/android-sdk/ndk/* | tail -1)"
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "NDK não achado: defina ANDROID_NDK_HOME" >&2
    exit 1
fi
NDK="$ANDROID_NDK_HOME"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

alvo="${1:-tudo}"
if [ "$alvo" = "arm64" ] || [ "$alvo" = "tudo" ]; then
    mkdir -p "$APP/arm64-v8a"
    echo "==> arm64"
    CC="$BIN/aarch64-linux-android21-clang" CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
        go build -ldflags="-s -w" -o "$APP/arm64-v8a/libwabridge_core.so" .
fi
if [ "$alvo" = "x86_64" ] || [ "$alvo" = "tudo" ]; then
    mkdir -p "$APP/x86_64"
    echo "==> x86_64"
    CC="$BIN/x86_64-linux-android21-clang" CGO_ENABLED=1 GOOS=android GOARCH=amd64 \
        go build -ldflags="-s -w" -o "$APP/x86_64/libwabridge_core.so" .
fi

ls -lh "$APP"/*/libwabridge_core.so
