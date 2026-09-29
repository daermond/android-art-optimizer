param(
    [Parameter(Mandatory=$true)][string]$Keystore,
    [Parameter(Mandatory=$true)][string]$Alias
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $Keystore)) { throw "Keystore not found: $Keystore" }
gh auth status | Out-Null
$repo = gh repo view --json nameWithOwner -q .nameWithOwner
$storeSecure = Read-Host "Keystore password" -AsSecureString
$keySecure = Read-Host "Key password" -AsSecureString
$storePassword = [System.Net.NetworkCredential]::new('', $storeSecure).Password
$keyPassword = [System.Net.NetworkCredential]::new('', $keySecure).Password
$bytes = [IO.File]::ReadAllBytes((Resolve-Path $Keystore))
$base64 = [Convert]::ToBase64String($bytes)
$base64 | gh secret set ANDROID_KEYSTORE_BASE64 --repo $repo
$storePassword | gh secret set ANDROID_KEYSTORE_PASSWORD --repo $repo
$Alias | gh secret set ANDROID_KEY_ALIAS --repo $repo
$keyPassword | gh secret set ANDROID_KEY_PASSWORD --repo $repo
$storePassword = $null; $keyPassword = $null; $base64 = $null
Write-Host "Signing secrets configured for $repo"
