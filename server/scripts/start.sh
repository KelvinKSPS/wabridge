#!/usr/bin/env bash
# Starts the bridge server using .env.
# (.env takes priority over the environment: `set -a` exports everything from the file)
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$DIR/.env}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

cd "$DIR"
exec node "$DIR/dist/index.js"
