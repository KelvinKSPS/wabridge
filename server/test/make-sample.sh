#!/usr/bin/env bash
# Gera test/sample.ogg (áudio de teste) usando ffmpeg.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=440:duration=3" \
  -c:a libopus -b:a 32k -ar 48000 \
  "$DIR/sample.ogg"
echo "gerado: $DIR/sample.ogg"
