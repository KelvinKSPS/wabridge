#!/usr/bin/env bash
# Esta board é ARM64, mas o aapt2 do Android SDK é x86-64.
# O script monta um sysroot x86 mínimo (glibc + libgcc) e cria um wrapper
# que roda o aapt2 via qemu-user. Depois é só apontar o AGP para ele:
#
#   android.aapt2FromMavenOverride=<wrapper>
#
# (o gradle.properties do projeto já faz isso; o caminho do wrapper é
#  ~/.local/bin/aapt2 — o AGP exige que o override termine em "aapt2")
set -euo pipefail

SYSROOT="${SYSROOT:-$HOME/.local/share/wabridge-x86root}"
WRAPPER="$HOME/.local/bin/aapt2"
AAPT2="${AAPT2:-/home/orangepi/android-sdk/build-tools/35.0.0/aapt2}"
DL="$(mktemp -d)"

if [[ ! -f "$SYSROOT/lib64/ld-linux-x86-64.so.2" ]]; then
  echo "[setup] baixando glibc/libgcc amd64..."
  cd "$DL"
  curl -sO "http://archive.ubuntu.com/ubuntu/pool/main/g/glibc/libc6_2.35-0ubuntu3.15_amd64.deb"
  curl -sO "http://archive.ubuntu.com/ubuntu/pool/main/g/gcc-12/libgcc-s1_12.3.0-1ubuntu1~22.04.3_amd64.deb"
  mkdir -p "$SYSROOT"
  dpkg-deb -x libc6_*.deb "$SYSROOT"
  dpkg-deb -x libgcc-s1_*.deb "$SYSROOT"
  rm -f "$SYSROOT/lib64/ld-linux-x86-64.so.2"
  cp "$SYSROOT/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2" "$SYSROOT/lib64/"
  cd /
  rm -rf "$DL"
fi

mkdir -p "$(dirname "$WRAPPER")"
cat > "$WRAPPER" <<EOF
#!/bin/sh
exec qemu-x86_64-static -L $SYSROOT $AAPT2 "\$@"
EOF
chmod +x "$WRAPPER"

# O override é por máquina: fica no gradle.properties do GRADLE_USER_HOME,
# para não viajar no repositório e quebrar o build em outras máquinas.
GRADLE_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
mkdir -p "$(dirname "$GRADLE_PROPS")"
if ! grep -q "aapt2FromMavenOverride" "$GRADLE_PROPS" 2>/dev/null; then
  printf '\n# Board ARM64: o aapt2 do SDK e x86-64, roda via qemu-user\nandroid.aapt2FromMavenOverride=%s\n' "$WRAPPER" >> "$GRADLE_PROPS"
fi

echo "[setup] wrapper: $WRAPPER"
echo "[setup] gradle.properties: $GRADLE_PROPS"
"$WRAPPER" version
