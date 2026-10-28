param([string]$Device)

$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\gradlew.bat" -p $PSScriptRoot assembleRelease --max-workers=1
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk"
Write-Host "APK: $apk"

if ($Device) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { throw 'Set ANDROID_HOME before installing to a device.' }
    $adb = Join-Path $sdk 'platform-tools/adb.exe'
    & $adb -s $Device install -r $apk
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & $adb -s $Device shell am start -n app.momiji.tv/.MainActivity
}
