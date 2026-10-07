param([Parameter(Mandatory)][string]$Target)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $PSScriptRoot 'build/craft-assets/licenses'
New-Item -ItemType Directory -Path $output -Force | Out-Null
function Copy-Notice([string]$Source, [string]$Relative) {
    $destination = Join-Path $output $Relative
    New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $Source -Destination $destination
}
$metadata = & cargo metadata --locked --format-version 1 --filter-platform $Target | ConvertFrom-Json
if ($LASTEXITCODE) { throw 'Could not collect dependency licenses' }
$inventory = foreach ($package in $metadata.packages) {
    $directory = Split-Path $package.manifest_path -Parent
    $files = @(Get-ChildItem -LiteralPath $directory -File | Where-Object { $_.Name -match '^(LICENSE|COPYING|NOTICE|OFL)' })
    if ($package.license_file) { $files += Get-Item -LiteralPath (Join-Path $directory $package.license_file) }
    foreach ($notice in ($files | Sort-Object FullName -Unique)) {
        Copy-Notice $notice.FullName "rust/$($package.name)-$($package.version)/$($notice.Name)"
    }
    [pscustomobject]@{name=$package.name; version=$package.version; license=$package.license; source=$package.repository}
}
$inventory | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $output 'rust-packages.json') -Encoding utf8
foreach ($name in @('LICENSE-MIT', 'LICENSE-APACHE', 'NOTICE', 'ATTRIBUTION.md', 'ASSETS.md')) {
    $path = Join-Path $repo $name
    if (Test-Path -LiteralPath $path) { Copy-Notice $path $name }
}
Get-ChildItem -LiteralPath (Join-Path $repo 'assets') -Recurse -File |
    Where-Object { $_.Name -match '^(LICENSE|COPYING|NOTICE|OFL|ATTRIBUTION)' } | ForEach-Object {
        Copy-Notice $_.FullName ([IO.Path]::GetRelativePath($repo, $_.FullName))
    }
if ($env:CRAFT_FONTS_DIR) {
    Get-ChildItem -LiteralPath $env:CRAFT_FONTS_DIR -Recurse -File |
        Where-Object { $_.Name -match '^(LICENSE|COPYING|NOTICE|OFL|ATTRIBUTION)' } | ForEach-Object {
            Copy-Notice $_.FullName ('craft-fonts/' + [IO.Path]::GetRelativePath($env:CRAFT_FONTS_DIR, $_.FullName))
        }
}
@'
AndroidX GameActivity, AppCompat, Core and their AndroidX dependencies
Copyright The Android Open Source Project. Apache License 2.0 (LICENSE-APACHE).
https://android.googlesource.com/platform/frameworks/support/
Kotlin standard library: Copyright JetBrains s.r.o. Apache License 2.0.
https://github.com/JetBrains/kotlin
'@ | Set-Content -LiteralPath (Join-Path $output 'android-libraries.txt') -Encoding utf8
