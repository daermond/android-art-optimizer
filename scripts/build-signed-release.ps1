param([string]$Version = '0.1.0')
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$') { throw 'Use a major.minor.patch version.' }
$parts = $Version.Split('.') | ForEach-Object { [long]$_ }
if ($parts[1] -gt 999 -or $parts[2] -gt 999) { throw 'Minor and patch versions must be at most 999.' }
$versionCode = $parts[0] * 1000000 + $parts[1] * 1000 + $parts[2]
if ($versionCode -le 0 -or $versionCode -gt 2100000000) { throw 'Version code must fit Android limits.' }
$repoRoot = Split-Path $PSScriptRoot -Parent
$keystore = Join-Path $repoRoot 'secrets/android-art-optimizer-release.p12'
$passwordFile = Join-Path $repoRoot 'secrets/signing-password.dpapi'
if (!(Test-Path -LiteralPath $keystore) -or !(Test-Path -LiteralPath $passwordFile)) {
    throw 'Run scripts/create-signing-key.ps1 first.'
}
$secure = Get-Content -LiteralPath $passwordFile | ConvertTo-SecureString
$password = [System.Net.NetworkCredential]::new('', $secure).Password
$names = @('ANDROID_KEYSTORE_PATH','ANDROID_KEYSTORE_PASSWORD','ANDROID_KEY_ALIAS','ANDROID_KEY_PASSWORD','VERSION_NAME','VERSION_CODE')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $repoRoot
try {
    $env:ANDROID_KEYSTORE_PATH = $keystore
    $env:ANDROID_KEYSTORE_PASSWORD = $password
    $env:ANDROID_KEY_ALIAS = 'art-optimizer'
    $env:ANDROID_KEY_PASSWORD = $password
    $env:VERSION_NAME = $Version
    $env:VERSION_CODE = [string]$versionCode
    & ./gradlew.bat --no-daemon lint testDebugUnitTest assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Signed release checks/build failed.' }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    $password = $null
    Pop-Location
}
