param([switch]$Android, [switch]$Device)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $taskArgs = @(':core-engine:test', ':platform-jvm:test', ':memory-core:test', ':contract-fixtures:run', '--console=plain')
    if ($Android) { $taskArgs += @('-Pandroid=true', ':platform-android:assembleRelease', ':platform-android:assembleDebugAndroidTest') }
    if ($Device) { $taskArgs += @('-Pandroid=true', ':platform-android:connectedDebugAndroidTest') }
    & .\gradlew.bat @taskArgs
    if ($LASTEXITCODE -ne 0) { throw 'Backend validation failed' }
} finally { Pop-Location }
