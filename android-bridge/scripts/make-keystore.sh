#!/usr/bin/env bash
# Gera o keystore de release do app e escreve o keystore.properties.
#
#   ./scripts/make-keystore.sh [senha] [caminho/do.jks]
#
# O script é idempotente: se o .jks já existir, não sobrescreve.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PASS="${1:-wabridge}"
JKS="${2:-$DIR/wabridge.jks}"
ALIAS="wabridge"

if [[ -f "$JKS" ]]; then
  echo "keystore já existe: $JKS (mantido)"
else
  keytool -genkeypair -v \
    -keystore "$JKS" \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=WA Bridge, OU=Home, O=wabridge, C=BR"
  echo "keystore criado: $JKS"
fi

cat > "$DIR/keystore.properties" <<EOF
storeFile=$JKS
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF

echo "keystore.properties escrito em $DIR/keystore.properties"
echo
echo "Build do APK release:"
echo "  cd $DIR && ./gradlew assembleRelease"
