# Validação remota do wabridge no emulador (Windows).
# Executado pelo agente da outra máquina:
#   C:\Windows\System32\curl.exe -sL https://raw.githubusercontent.com/KelvinKSPS/wabridge/master/scripts/remote-validate.ps1 -o v.ps1
#   C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -ExecutionPolicy Bypass -File v.ps1

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
$emu = "$env:ANDROID_HOME\emulator\emulator.exe"

# git pode não estar no PATH: tenta o caminho padrão do Git for Windows
$git = "git"
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
  foreach ($c in @("C:\Program Files\Git\cmd\git.exe", "C:\Program Files\Git\bin\git.exe")) {
    if (Test-Path $c) { $git = $c; break }
  }
}

Say "### Ambiente"
Say ("git:    " + $git)
Say ("adb:    " + (Test-Path $adb))
Say ("emu:    " + (Test-Path $emu))
Say ("java:   " + (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Select-Object -First 1))

Set-Location $repo

Say "### git pull"
Say ((& $git pull 2>&1) -join "`n")

Set-Location "$repo\android-bridge"

Say "### gradlew assembleDebug assembleRelease"
$build = (& .\gradlew.bat assembleDebug assembleRelease --console=plain 2>&1) -join "`n"
$build | Select-Object -Last 30 | ForEach-Object { Say $_ }
$buildOk = $build -match "BUILD SUCCESSFUL"

$apk = "$repo\android-bridge\app\build\outputs\apk\release\app-release.apk"
Say "### APK release"
if (Test-Path $apk) {
  Say ("apk release: " + (Get-Item $apk).Length + " bytes")
} else {
  Say "APK release NAO foi gerado"
}

# ---- emulador ----------------------------------------------------------
Say "### dispositivos"
$devices = (& $adb devices 2>&1) -join "`n"
Say $devices

if ($devices -notmatch "device$") {
  Say "### subindo o emulador"
  $avds = (& $emu -list-avds 2>&1) -join "`n"
  Say ("avds: " + $avds)
  $first = ($avds -split "`n" | Where-Object { $_.Trim() -ne "" } | Select-Object -First 1)
  if ($first) {
    Start-Process -FilePath $emu -ArgumentList "-avd", $first.Trim(), "-no-snapshot", "-no-boot-anim" | Out-Null
    Say "esperando o emulador subir"
    & $adb wait-for-device
    for ($i = 0; $i -lt 60; $i++) {
      $boot = (& $adb shell getprop sys.boot_completed 2>&1) -join ""
      if ($boot -match "1") { Say "emulador pronto"; break }
      Start-Sleep -Seconds 5
    }
  } else {
    Say "nenhum AVD encontrado"
  }
  $devices = (& $adb devices 2>&1) -join "`n"
  Say $devices
}

$hasDevice = $devices -match "device$"

if ($hasDevice) {
  Say "### instalando o release"
  & $adb uninstall com.orangepi.wabridge 2>&1 | Out-Null
  Say ((& $adb install -r $apk 2>&1) -join "`n")

  Say "### permissoes"
  Say ((& $adb shell pm grant com.orangepi.wabridge android.permission.RECORD_AUDIO 2>&1) -join "`n")

  Say "### abrindo o app"
  & $adb logcat -c
  & $adb shell am start -n com.orangepi.wabridge/com.orangepi.wabridge.MainActivity 2>&1 | Out-Null
  Start-Sleep -Seconds 8

  Say "### tela principal"
  Say ((& $adb shell dumpsys activity activities 2>&1 | Select-String "topResumedActivity" | Select-Object -First 2) -join "`n")

  Say "### chamada de teste"
  & $adb shell am start-foreground-service -n com.orangepi.wabridge/com.orangepi.wabridge.BridgeForegroundService -a com.orangepi.wabridge.TEST_CALL 2>&1 | Out-Null
  Start-Sleep -Seconds 8

  Say "### atividade em primeiro plano depois da chamada"
  Say ((& $adb shell dumpsys activity activities 2>&1 | Select-String "topResumedActivity" | Select-Object -First 2) -join "`n")

  Say "### screenshot"
  & $adb shell screencap -p /sdcard/wabridge.png 2>&1 | Out-Null
  & $adb pull /sdcard/wabridge.png "$repo\wabridge-emulador.png" 2>&1 | Out-Null
  Say "screenshot em wabridge-emulador.png"

  Say "### crash?"
  $crash = (& $adb logcat -d 2>&1 | Select-String "FATAL EXCEPTION|ANR in com.orangepi.wabridge" | Select-Object -First 20) -join "`n"
  if ([string]::IsNullOrWhiteSpace($crash)) { Say "sem crash no logcat" } else { Say $crash }

  Say "### logs do app"
  Say ((& $adb logcat -d 2>&1 | Select-String "WatchCall|BridgeFg|BridgeConn|WsManager|CallActivity" | Select-Object -Last 40) -join "`n")
} else {
  Say "sem emulador conectado: validacao de runtime nao foi feita"
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

Set-Location $repo
& $git add RELATORIO-REMOTO.md 2>&1 | Out-Null
& $git commit -m "relatorio: validacao remota no emulador ($status)" 2>&1 | Out-Null
& $git push 2>&1 | Out-Null
Say "### commit e push feitos"

Write-Host "WABRIDGE-RESULTADO: $status"
