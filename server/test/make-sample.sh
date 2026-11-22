#!/usr/bin/env bash
# Generates test/sample.ogg (test audio) using ffmpeg.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=440:duration=3" \
  -c:a libopus -b:a 32k -ar 48000 \
  "$DIR/sample.ogg"
echo "gerado: $DIR/sample.ogg"
