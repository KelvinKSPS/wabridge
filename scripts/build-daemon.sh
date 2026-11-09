#!/usr/bin/env bash
# Compila o daemon Go (motor do WhatsApp) para Android ARM64 e coloca
# no jniLibs do app. Precisa do Go instalado (qualquer versão 1.22+).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../embedded-core"
OUT="../android-bridge/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"

echo "==> go test"
go test ./...

echo "==> build android/arm64"
GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
  go build -ldflags="-s -w" -o "$OUT/libwabridge_core.so" .

ls -lh "$OUT/libwabridge_core.so"
echo "pronto: $OUT/libwabridge_core.so"
