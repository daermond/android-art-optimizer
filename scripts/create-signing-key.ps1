$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$secretDir = Join-Path $repoRoot 'secrets'
$keystore = Join-Path $secretDir 'android-art-optimizer-release.p12'
$passwordFile = Join-Path $secretDir 'signing-password.dpapi'
if ((Test-Path -LiteralPath $keystore) -or (Test-Path -LiteralPath $passwordFile)) {
    if (!(Test-Path -LiteralPath $keystore) -or !(Test-Path -LiteralPath $passwordFile)) {
        throw 'Incomplete signing credentials. Restore the missing file; never replace an existing release key.'
    }
    Write-Host 'Existing release signing credentials retained.'
    exit 0
}
New-Item -ItemType Directory -Path $secretDir -Force | Out-Null
$random = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($random)
$password = [Convert]::ToBase64String($random)
$secure = ConvertTo-SecureString $password -AsPlainText -Force
$secure | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordFile -Encoding ascii
try {
    $env:ART_SIGNING_PASSWORD = $password
    & keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias art-optimizer `
        -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Android ART Optimizer' `
        -storepass:env ART_SIGNING_PASSWORD -keypass:env ART_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Signing-key generation failed.' }
} finally {
    Remove-Item Env:ART_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    [Array]::Clear($random, 0, $random.Length)
}
Write-Host "Release key saved in $secretDir. Password is protected with Windows DPAPI for this account."
Write-Host 'No credentials were uploaded. Keep these files for future updates.'
