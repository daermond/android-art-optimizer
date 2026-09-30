param(
    [Parameter(Mandatory=$true)][string]$Keystore,
    [Parameter(Mandatory=$true)][string]$Alias,
    [string]$PasswordFile
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $Keystore)) { throw "Keystore not found: $Keystore" }
gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'GitHub authentication failed.' }
$repo = gh repo view --json nameWithOwner -q .nameWithOwner
if ($LASTEXITCODE -ne 0 -or !$repo) { throw 'Cannot resolve GitHub repository.' }
if ($PasswordFile) {
    $storeSecure = Get-Content -LiteralPath $PasswordFile | ConvertTo-SecureString
    $keySecure = $storeSecure
} else {
    $storeSecure = Read-Host "Keystore password" -AsSecureString
    $keySecure = Read-Host "Key password" -AsSecureString
}
$storePassword = [System.Net.NetworkCredential]::new('', $storeSecure).Password
$keyPassword = [System.Net.NetworkCredential]::new('', $keySecure).Password
$bytes = [IO.File]::ReadAllBytes((Resolve-Path $Keystore))
$base64 = [Convert]::ToBase64String($bytes)
try {
    $base64 | gh secret set ANDROID_KEYSTORE_BASE64 --repo $repo
    if ($LASTEXITCODE -ne 0) { throw 'Keystore upload failed.' }
    $storePassword | gh secret set ANDROID_KEYSTORE_PASSWORD --repo $repo
    if ($LASTEXITCODE -ne 0) { throw 'Keystore password upload failed.' }
    $Alias | gh secret set ANDROID_KEY_ALIAS --repo $repo
    if ($LASTEXITCODE -ne 0) { throw 'Alias upload failed.' }
    $keyPassword | gh secret set ANDROID_KEY_PASSWORD --repo $repo
    if ($LASTEXITCODE -ne 0) { throw 'Key password upload failed.' }
} finally {
    [Array]::Clear($bytes, 0, $bytes.Length)
    $storePassword = $null; $keyPassword = $null; $base64 = $null
}
Write-Host "Signing secrets configured for $repo"
