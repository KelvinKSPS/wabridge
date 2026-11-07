#!/usr/bin/env bash
# Sobe o servidor da ponte usando o .env.
# (o .env tem prioridade sobre o ambiente: `set -a` exporta tudo que vier do arquivo)
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
