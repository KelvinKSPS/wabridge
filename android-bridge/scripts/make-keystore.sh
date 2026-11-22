#!/usr/bin/env bash
# Generates the app's release keystore and writes keystore.properties.
#
#   ./scripts/make-keystore.sh [password] [path/to/file.jks]
#
# The script is idempotent: if the .jks already exists, it does not overwrite it.
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
