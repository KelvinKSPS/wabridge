# Validação remota do wabridge no emulador (Windows).
# Executado pelo agente da outra máquina:
#   curl -o v.ps1 https://raw.githubusercontent.com/KelvinKSPS/wabridge/master/scripts/remote-validate.ps1 && powershell -ExecutionPolicy Bypass -File v.ps1
#
# O resultado vai para RELATORIO.md na raiz do repo (commit + push).

$ErrorActionPreference = "Continue"
$repo = "C:\Users\sarke\wabridge"
$log = @()

function Say($text) {
  $script:log += $text
  Write-Host $text
}

$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME = "C:\Users\sarke\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"

Say "### Ambiente"
Say ("java: " + (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Select-Object -First 1))
Say ("adb:  " + $adb)

Set-Location $repo

Say "### git pull"
Say ((git pull 2>&1) -join "`n")

Say "### gradlew assembleDebug assembleRelease"
$build = (& .\gradlew.bat assembleDebug assembleRelease --console=plain 2>&1) -join "`n"
Say $build
$buildOk = $build -match "BUILD SUCCESSFUL"

$apk = "$repo\android-bridge\app\build\outputs\apk\release\app-release.apk"
Say "### APK release"
if (Test-Path $apk) {
  Say ("apk existe: " + (Get-Item $apk).Length + " bytes")
} else {
  Say "APK release NAO foi gerado"
}

Say "### emulador"
& $adb devices
$devices = (& $adb devices) -join "`n"
$hasDevice = $devices -match "device"

if ($hasDevice) {
  Say "### instalando o release"
  & $adb uninstall com.orangepi.wabridge 2>&1 | Out-Null
  Say ((& $adb install -r $apk 2>&1) -join "`n")

  Say "### permissoes"
  Say ((& $adb shell pm grant com.orangepi.wabridge android.permission.RECORD_AUDIO 2>&1) -join "`n")

  Say "### iniciando o app"
  & $adb logcat -c
  & $adb shell am start -n com.orangepi.wabridge/com.orangepi.wabridge.MainActivity 2>&1 | Out-Null
  Start-Sleep -Seconds 6

  Say "### tela de chamada de teste"
  & $adb shell am start-foreground-service -n com.orangepi.wabridge/com.orangepi.wabridge.BridgeForegroundService -a com.orangepi.wabridge.TEST_CALL 2>&1 | Out-Null
  Start-Sleep -Seconds 6

  Say "### atividade em primeiro plano"
  Say ((& $adb shell dumpsys activity activities 2>&1 | Select-String "topResumedActivity|mResumedActivity" | Select-Object -First 4) -join "`n")

  Say "### screenshot"
  & $adb shell screencap -p /sdcard/wabridge.png 2>&1 | Out-Null
  & $adb pull /sdcard/wabridge.png "$repo\wabridge-emulador.png" 2>&1 | Out-Null
  Say "screenshot em wabridge-emulador.png"

  Say "### crash?"
  $crash = (& $adb logcat -d 2>&1 | Select-String "FATAL EXCEPTION|AndroidRuntime.*wabridge|ANR in com.orangepi.wabridge") -join "`n"
  if ([string]::IsNullOrWhiteSpace($crash)) {
    Say "sem crash no logcat"
  } else {
    Say $crash
  }

  Say "### logs do app"
  Say ((& $adb logcat -d -s WatchCall:B BridgeFg:B BridgeConn:B WsManager:B 2>&1) -join "`n")
} else {
  Say "sem emulador conectado, pulando o teste de instalacao"
}

$status = if ($buildOk -and (Test-Path $apk) -and $hasDevice) { "OK" } else { "FALHOU" }

$report = @"
# Relatorio da validacao remota

Gerado pela maquina Windows (16 GB) via scripts/remote-validate.ps1.

## Resultado

WABRIDGE-RESULTADO: $status

## Detalhes

````
$($log -join "`n")
````
"@

Set-Content -Path "$repo\RELATORIO-REMOTO.md" -Value $report -Encoding UTF8
Say "### RELATORIO-REMOTO.md escrito"

git add RELATORIO-REMOTO.md
git commit -m "relatorio: validacao remota no emulador ($status)" 2>&1 | Out-Null
git push 2>&1 | Out-Null
Say "### commit e push feitos"

Write-Host "WABRIDGE-RESULTADO: $status"
