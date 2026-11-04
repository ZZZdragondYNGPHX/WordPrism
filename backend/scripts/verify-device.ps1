param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$ProcessRestart
)
$ErrorActionPreference = 'Stop'
$taskBackend = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskAdb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' } else { 'adb' }
if (-not $IsWindows -and $env:ANDROID_HOME) { $taskAdb = Join-Path $env:ANDROID_HOME 'platform-tools/adb' }
$taskPackage = 'dev.wordprism.android.test'
$taskRunner = "$taskPackage/androidx.test.runner.AndroidJUnitRunner"
$taskReports = Join-Path $taskBackend 'build/reports/device'
New-Item -ItemType Directory -Force -Path $taskReports | Out-Null
& $taskAdb -s $Serial install -r (Join-Path $taskBackend 'platform-android/build/outputs/apk/androidTest/debug/platform-android-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed' }
function Invoke-BackendInstrumentation([string]$TestClass, [string]$LogName) {
    $taskOutput = & $taskAdb -s $Serial shell am instrument -w -r -e class $TestClass $taskRunner 2>&1
    $taskOutput | Tee-Object -FilePath (Join-Path $taskReports $LogName)
    $taskText = $taskOutput -join "`n"
    if ($LASTEXITCODE -ne 0 -or $taskText -notmatch 'OK \([0-9]+ tests?\)' -or $taskText -match 'FAILURES!!!') {
        throw "Android instrumentation failed: $TestClass"
    }
}
Invoke-BackendInstrumentation 'dev.wordprism.android.AndroidCoreTest' 'core.log'
if ($ProcessRestart) {
    Invoke-BackendInstrumentation 'dev.wordprism.android.ProcessRestartProbeTest#aWriteCommittedState' 'process-write.log'
    & $taskAdb -s $Serial shell am force-stop $taskPackage
    if ($LASTEXITCODE -ne 0) { throw 'Could not force-stop the isolated test package' }
    Invoke-BackendInstrumentation 'dev.wordprism.android.ProcessRestartProbeTest#bReadAfterProcessRestart' 'process-read.log'
}
