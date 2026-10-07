param(
    [ValidateSet('arm64-v8a', 'x86_64')][string[]]$Abi = @('arm64-v8a'),
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$NdkVersion = '28.2.13676358',
    [string]$Gradle = '',
    [switch]$Release,
    [switch]$Check,
    [switch]$PackageOnly
)
$ErrorActionPreference = 'Stop'
if ($Release) {
    if ($Abi.Count -ne 1 -or $Abi[0] -ne 'arm64-v8a') { throw 'Distribution APKs must contain arm64-v8a only' }
    foreach ($required in @('CRAFT_ANDROID_KEYSTORE', 'CRAFT_ANDROID_STORE_PASSWORD', 'CRAFT_ANDROID_KEY_ALIAS')) {
        if (![Environment]::GetEnvironmentVariable($required)) { throw "Set $required in the build process environment" }
    }
    if (!(Test-Path -LiteralPath $env:CRAFT_ANDROID_KEYSTORE)) { throw 'Signing keystore does not exist' }
}
$profile = if ($Release) { 'release' } else { 'debug' }
$jniDirectory = if ($Release) { 'rust-release' } else { 'rust' }
if (!$Gradle) { $Gradle = Join-Path $PSScriptRoot 'gradlew.bat' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$appName = Split-Path $repo -Leaf
$label = (Get-Culture).TextInfo.ToTitleCase($appName.Replace('craft', '')) + 'Craft'
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$toolchain = Join-Path $Sdk "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin"
if (!(Test-Path -LiteralPath $toolchain)) { throw "Android NDK $NdkVersion not found at $toolchain" }
$env:ANDROID_HOME = $Sdk
$env:ANDROID_NDK_HOME = Join-Path $Sdk "ndk/$NdkVersion"
if (!$env:CARGO_BUILD_JOBS) { $env:CARGO_BUILD_JOBS = '4' }
$env:CARGO_PROFILE_DEV_DEBUG = '0'
$env:CARGO_PROFILE_DEV_INCREMENTAL = 'false'
if (!$env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR = Join-Path $repo 'target' }
$env:RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
if ($Release) {
    $env:CARGO_PROFILE_RELEASE_STRIP = 'symbols'
    $workspaceParent = Split-Path $repo -Parent
    $env:RUSTFLAGS += " --remap-path-prefix=$workspaceParent=/workspace --remap-path-prefix=$env:USERPROFILE=/builder"
}
Push-Location $repo
try {
    foreach ($arch in $Abi) {
        $target = if ($arch -eq 'arm64-v8a') { 'aarch64-linux-android' } else { 'x86_64-linux-android' }
        $prefix = $target.Replace('-', '_')
        [Environment]::SetEnvironmentVariable("CARGO_TARGET_$($prefix.ToUpper())_LINKER", (Join-Path $toolchain "$($target)26-clang.cmd"), 'Process')
        [Environment]::SetEnvironmentVariable("CC_$prefix", (Join-Path $toolchain "$($target)26-clang.cmd"), 'Process')
        [Environment]::SetEnvironmentVariable("CXX_$prefix", (Join-Path $toolchain "$($target)26-clang++.cmd"), 'Process')
        [Environment]::SetEnvironmentVariable("AR_$prefix", (Join-Path $toolchain 'llvm-ar.exe'), 'Process')
        if (!$PackageOnly) {
            $action = if ($Check) { 'check' } else { 'build' }
            $cargoArgs = @($action, '-p', $appName, '--lib', '--locked', '--target', $target)
            if ($Release) { $cargoArgs += '--release' }
            & cargo @cargoArgs
            if ($LASTEXITCODE) { throw "Cargo $action failed for $target" }
        }
        if (!$Check) {
            $destination = Join-Path $PSScriptRoot "build/$jniDirectory/$arch"
            New-Item -ItemType Directory -Path $destination -Force | Out-Null
            Copy-Item -LiteralPath (Join-Path $env:CARGO_TARGET_DIR "$target/$profile/lib$appName.so") -Destination $destination
        }
    }
    if ($Check) { return }
    & (Join-Path $PSScriptRoot 'licenses.ps1') -Target $target
    $drawables = Join-Path $PSScriptRoot 'build/craft-res/drawable'
    New-Item -ItemType Directory -Path $drawables -Force | Out-Null
    $icon = Join-Path $repo "assets/app-icon/hicolor/256x256/apps/ai.storyteller.$appName.png"
    if (!(Test-Path -LiteralPath $icon)) { throw "App icon not found: $icon" }
    Copy-Item -LiteralPath $icon -Destination (Join-Path $drawables 'craft_icon.png')
    $assemble = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
    & $Gradle -p $PSScriptRoot --no-daemon --max-workers=2 "-PcraftApp=$appName" "-PcraftLabel=$label" "-PcraftAbis=$($Abi -join ',')" $assemble
    if ($LASTEXITCODE) { throw 'Android APK packaging failed' }
} finally { Pop-Location }
