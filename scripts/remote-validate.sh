#!/usr/bin/env bash
# Validação remota do wabridge no emulador (funciona no git bash do Windows).
#   curl -sL https://raw.githubusercontent.com/KelvinKSPS/wabridge/master/scripts/remote-validate.sh -o v.sh && bash v.sh
set -uo pipefail

REPO="${REPO:-$HOME/wabridge}"
SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
[ -d "$SDK" ] || SDK="$HOME/AppData/Local/Android/Sdk"
ADB="$SDK/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$SDK/platform-tools/adb"
LOG="/tmp/wabridge-remote.log"

say() { echo "$*" | tee -a "$LOG"; }
: > "$LOG"

say "### ambiente"
say "repo: $REPO"
say "sdk:  $SDK"

cd "$REPO" || { say "repo nao encontrado"; exit 1; }

say "### git pull"
git pull 2>&1 | tee -a "$LOG"

say "### gradlew assembleDebug assembleRelease"
./gradlew.bat assembleDebug assembleRelease --console=plain > /tmp/wabridge-build.log 2>&1
BUILD_RC=$?
tail -25 /tmp/wabridge-build.log | tee -a "$LOG"
say "gradle exit=$BUILD_RC"

APK="$REPO/android-bridge/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  say "apk release: $(stat -c%s "$APK" 2>/dev/null || wc -c < "$APK") bytes"
else
  say "APK release NAO gerado"
fi

DEVICES=$("$ADB" devices 2>&1)
say "### dispositivos"
echo "$DEVICES" | tee -a "$LOG"

if echo "$DEVICES" | grep -q "device$"; then
  say "### instala"
  "$ADB" uninstall com.orangepi.wabridge >/dev/null 2>&1
  "$ADB" install -r "$APK" 2>&1 | tee -a "$LOG"

  say "### permissoes"
  "$ADB" shell pm grant com.orangepi.wabridge android.permission.RECORD_AUDIO 2>&1 | tee -a "$LOG"

  say "### abre o app"
  "$ADB" logcat -c
  "$ADB" shell am start -n com.orangepi.wabridge/com.orangepi.wabridge.MainActivity >/dev/null 2>&1
  sleep 6

  say "### chamada de teste"
  "$ADB" shell am start-foreground-service -n com.orangepi.wabridge/com.orangepi.wabridge.BridgeForegroundService -a com.orangepi.wabridge.TEST_CALL 2>&1 | tee -a "$LOG"
  sleep 6

  say "### atividade em primeiro plano"
  "$ADB" shell dumpsys activity activities 2>&1 | grep -iE "topResumedActivity|mResumedActivity" | head -4 | tee -a "$LOG"

  say "### screenshot"
  "$ADB" shell screencap -p /sdcard/wabridge.png >/dev/null 2>&1
  "$ADB" pull /sdcard/wabridge.png "$REPO/wabridge-emulador.png" 2>&1 | tee -a "$LOG"

  say "### crash?"
  CRASH=$("$ADB" logcat -d 2>&1 | grep -iE "FATAL EXCEPTION|ANR in com.orangepi.wabridge" | head -20)
  if [ -z "$CRASH" ]; then say "sem crash no logcat"; else echo "$CRASH" | tee -a "$LOG"; fi

  say "### logs do app"
  "$ADB" logcat -d 2>&1 | grep -E "WatchCall|BridgeFg|BridgeConn|WsManager|CallActivity" | tail -40 | tee -a "$LOG"
else
  say "sem emulador conectado"
fi

if [ "$BUILD_RC" = "0" ] && [ -f "$APK" ]; then STATUS="OK"; else STATUS="FALHOU"; fi

{
  echo "# Relatorio da validacao remota"
  echo
  echo "Gerado pela maquina Windows (16 GB) via scripts/remote-validate.sh."
  echo
  echo "## Resultado"
  echo
  echo "WABRIDGE-RESULTADO: $STATUS"
  echo
  echo "## Detalhes"
  echo
  echo '```'
  cat "$LOG"
  echo '```'
} > "$REPO/RELATORIO-REMOTO.md"

say "### RELATORIO-REMOTO.md escrito"
cd "$REPO" && git add RELATORIO-REMOTO.md && git commit -m "relatorio: validacao remota no emulador ($STATUS)" >/dev/null 2>&1 && git push >/dev/null 2>&1
say "### commit e push feitos"
echo "WABRIDGE-RESULTADO: $STATUS"
