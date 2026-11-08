#!/usr/bin/env bash
# Ajuda com a Evolution API da ponte.
#   bash scripts/evolution.sh status   # estado da instância
#   bash scripts/evolution.sh qr       # salva o QR atual em /tmp/wabridge-qr.png
#   bash scripts/evolution.sh logs     # últimas linhas do container
set -euo pipefail

API="${EVOLUTION_API_URL:-http://127.0.0.1:8098}"
KEY="${EVOLUTION_API_KEY:-wabridge-dev}"
INST="${EVOLUTION_INSTANCE:-wabridge}"
BRIDGE="${BRIDGE_URL:-http://127.0.0.1:8097}"

case "${1:-status}" in
  status)
    curl -s -m 10 -H "apikey: $KEY" "$API/instance/fetchInstances" |
      python3 -c "
import sys, json
for i in json.load(sys.stdin):
    print(f\"{i['name']}: {i['connectionStatus']}  numero={i.get('number')}  motivo={i.get('disconnectionReasonCode')}\")
"
    ;;
  qr)
    # o QR da Evolution vai por webhook para a ponte
    curl -s -m 10 -H "apikey: $KEY" "$API/instance/connect/$INST" >/dev/null || true
    sleep 3
    if curl -sf -m 10 "$BRIDGE/qrcode.png" -o /tmp/wabridge-qr.png; then
      echo "QR salvo em /tmp/wabridge-qr.png (expira em ~30s — abra o painel em :8098/manager para gerar outro)"
    else
      echo "sem QR na ponte ainda (a Evolution manda a cada ~30s enquanto nao pareia)"
    fi
    ;;
  logs)
    docker logs --tail "${2:-40}" wabridge-evolution 2>&1 | tail -n "${2:-40}"
    ;;
  *)
    echo "uso: $0 {status|qr|logs}" >&2
    exit 1
    ;;
esac
