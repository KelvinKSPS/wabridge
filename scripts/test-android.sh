#!/usr/bin/env bash
# Teste de ponta a ponta no emulador Android (via adb).
#
# Faz sozinho: instala o APK, sobe o daemon, gera o código de pareamento,
# espera o pareamento, conecta o WebSocket e confirma que a nota de voz
# chega. O que depende de gente: digitar o código no WhatsApp e mandar a
# nota de voz — o script avisa na hora.
#
# Uso:
#   scripts/test-android.sh <apk> <ddd+numero>
#
# Exemplo:
#   scripts/test-android.sh app-debug.apk 19982617622

set -euo pipefail

APK="${1:-}"
PHONE="${2:-}"

if [ -z "$APK" ] || [ -z "$PHONE" ]; then
    echo "uso: $0 <apk> <ddd+numero>" >&2
    exit 2
fi
if [ ! -f "$APK" ]; then
    echo "APK não encontrado: $APK" >&2
    exit 2
fi

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-emulator-5554}"
PKG="com.orangepi.wabridge.debug"
ACT="$PKG/com.orangepi.wabridge.MainActivity"
PORT=8097
FWD=18097

diz() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

diz "instalando o APK"
"$ADB" -s "$SERIAL" uninstall "$PKG" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" install "$APK"
"$ADB" -s "$SERIAL" forward "tcp:$FWD" "tcp:$PORT"
"$ADB" -s "$SERIAL" logcat -c

diz "abrindo o app"
"$ADB" -s "$SERIAL" shell am start -n "$ACT" >/dev/null
sleep 12

diz "esperando o daemon"
for _ in $(seq 1 30); do
    curl -sf -m 3 "http://127.0.0.1:$FWD/health" >/dev/null 2>&1 && break
    sleep 2
done

diz "gerando o código de pareamento"
CODE=$(curl -sf -m 45 -X POST -H 'Content-Type: application/json' \
    -d "{\"phoneNumber\":\"$PHONE\"}" \
    "http://127.0.0.1:$FWD/auth/pair-code" | sed -n 's/.*"code":"\([^"]*\)".*/\1/p')

if [ -z "$CODE" ]; then
    echo "não consegui gerar o código (o daemon está no ar?)" >&2
    exit 1
fi
echo
echo "   ┌──────────────────────────────┐"
echo "   │   CÓDIGO:  $CODE          │"
echo "   └──────────────────────────────┘"
echo
echo "   WhatsApp → ⋮ → Aparelhos conectados → Conectar um aparelho"
echo "   → Conectar usando o número de telefone → $CODE"
echo
printf "   Pareou? (Enter para continuar, 'q' para sair) "
read -r resposta
if [ "$resposta" = "q" ]; then
    echo "tchau"
    exit 0
fi

diz "conferindo o pareamento"
for _ in $(seq 1 20); do
    LOGADO=$(curl -sf -m 5 "http://127.0.0.1:$FWD/auth/status" | sed -n 's/.*"loggedIn":\(true\|false\).*/\1/p')
    [ "$LOGADO" = "true" ] && break
    sleep 3
done
if [ "$LOGADO" != "true" ]; then
    echo "ainda não pareou. Rode de novo depois de digitar o código." >&2
    exit 1
fi
echo "   pareado como $(curl -sf -m 5 "http://127.0.0.1:$FWD/auth/status" | sed -n 's/.*"phone":"\([^"]*\)".*/\1/p')"

diz "ligando o serviço (WebSocket)"
"$ADB" -s "$SERIAL" shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
INICIAR=$("$ADB" -s "$SERIAL" shell cat /data/local/tmp/ui.xml | tr '<' '\n<' |
    grep -E 'text="iniciar"' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' |
    head -1 | grep -oE '[0-9]+,[0-9]+' | head -1)
if [ -z "$INICIAR" ]; then
    echo "não achei o botão INICIAR na tela" >&2
    exit 1
fi
"$ADB" -s "$SERIAL" shell input tap ${INICIAR%,*} ${INICIAR#*,}
sleep 8

CLIENTES=$(curl -sf -m 5 "http://127.0.0.1:$FWD/health" | sed -n 's/.*"clients":\([0-9]*\).*/\1/p')
if [ "$CLIENTES" != "1" ]; then
    echo "o app não conectou no daemon (clients=$CLIENTES)" >&2
    exit 1
fi
echo "   app conectado ao daemon"

diz "agora manda uma nota de voz pro teu próprio número ($PHONE)"
diz "e fica de olho: o app tem que tocar e abrir a tela de chamada"
echo
echo "   log do daemon (Ctrl+C para parar):"
"$ADB" -s "$SERIAL" logcat -v time -s WabridgeDaemon BridgeForegroundService
