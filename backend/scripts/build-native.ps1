param(
    [switch]$Android,
    [switch]$RunTests,
    [string]$NdkPath = $env:ANDROID_NDK_HOME
)
$ErrorActionPreference = 'Stop'
$taskCrate = Join-Path $PSScriptRoot '..\memory-native\Cargo.toml'
& cargo build --manifest-path $taskCrate --release --locked
if ($LASTEXITCODE -ne 0) { throw 'Host memory kernel build failed' }
if ($RunTests) {
    & cargo test --manifest-path $taskCrate --release --locked
    if ($LASTEXITCODE -ne 0) { throw 'Memory kernel tests failed' }
}
if ($Android) {
    if (-not $NdkPath) { throw 'Set ANDROID_NDK_HOME or pass -NdkPath (NDK 28.2.13676358)' }
    $taskNdk = (Resolve-Path -LiteralPath $NdkPath).Path
    $taskHost = if ($IsLinux) { 'linux-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'windows-x86_64' }
    $taskSuffix = if ($taskHost -eq 'windows-x86_64') { '.cmd' } else { '' }
    $taskBin = Join-Path $taskNdk "toolchains/llvm/prebuilt/$taskHost/bin"
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $taskBin "aarch64-linux-android26-clang$taskSuffix"
    $env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = Join-Path $taskBin "x86_64-linux-android26-clang$taskSuffix"
    & rustup target add aarch64-linux-android x86_64-linux-android
    if ($LASTEXITCODE -ne 0) { throw 'Android Rust targets are unavailable' }
    & cargo build --manifest-path $taskCrate --release --locked --target aarch64-linux-android --target x86_64-linux-android
    if ($LASTEXITCODE -ne 0) { throw 'Android memory kernel build failed' }
}
