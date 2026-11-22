#!/usr/bin/env bash
# Helper for the bridge's Evolution API.
#   bash scripts/evolution.sh status   # instance state
#   bash scripts/evolution.sh qr       # saves the current QR to /tmp/wabridge-qr.png
#   bash scripts/evolution.sh logs     # last lines of the container
#
# To pair WhatsApp: open the QR on a computer (panel :8098/manager or
# http://192.168.15.100:8097/qrcode) and scan it with the phone.
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
    # the Evolution QR goes via webhook to the bridge
    curl -s -m 10 -H "apikey: $KEY" "$API/instance/connect/$INST" >/dev/null || true
    sleep 3
    if curl -sf -m 10 "$BRIDGE/qrcode.png" -o /tmp/wabridge-qr.png; then
      echo "QR salvo em /tmp/wabridge-qr.png (expira em ~30s - abra o painel em :8098/manager para gerar outro)"
    else
      echo "sem QR na ponte ainda (a Evolution manda a cada ~30s enquanto nao parea)"
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
