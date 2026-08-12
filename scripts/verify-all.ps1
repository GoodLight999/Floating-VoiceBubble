$ErrorActionPreference = 'Stop'

$AppId = 'com.goodlight.floatingvoicebubble'
$Activity = "$AppId/.MainActivity"
$Apk = 'app\build\outputs\apk\debug\app-debug.apk'

& .\gradlew.bat --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }

$SdkRoot = $env:ANDROID_HOME
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if ($SdkRoot) {
    $BuildTools = Join-Path $SdkRoot 'build-tools\36.0.0'
    $ApkSigner = Join-Path $BuildTools 'apksigner.bat'
    $Aapt = Join-Path $BuildTools 'aapt.exe'
    if (Test-Path $ApkSigner) {
        & $ApkSigner verify --verbose --print-certs $Apk
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    if (Test-Path $Aapt) {
        & $Aapt dump badging $Apk | Select-String '^(package:|sdkVersion:|targetSdkVersion:|launchable-activity:)'
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
}

$Adb = Get-Command adb -ErrorAction SilentlyContinue
if ($Adb) {
    & adb get-state *> $null
    if ($LASTEXITCODE -eq 0) {
        & adb install -r -t $Apk
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & adb shell pm grant $AppId android.permission.RECORD_AUDIO
        & adb shell am start -W -n $Activity
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & .\gradlew.bat --no-daemon :app:connectedDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } else {
        Write-Host 'No Android device/emulator attached: connected runtime checks skipped.'
    }
} else {
    Write-Host 'adb not found: connected runtime checks skipped.'
}
