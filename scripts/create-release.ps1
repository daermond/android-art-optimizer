param([Parameter(Mandatory=$true)][string]$Version)
$ErrorActionPreference = "Stop"
$versionName = $Version.TrimStart('v')
if ($versionName -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$') { throw 'Use a major.minor.patch version.' }
$parts = $versionName.Split('.') | ForEach-Object { [long]$_ }
$code = $parts[0] * 1000000 + $parts[1] * 1000 + $parts[2]
if ($parts[1] -gt 999 -or $parts[2] -gt 999 -or $code -le 0 -or $code -gt 2100000000) { throw 'Version exceeds Android limits.' }
$tag = "v$versionName"
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
if (git status --porcelain) { throw "Working tree is not clean." }
if ($LASTEXITCODE -ne 0) { throw 'Cannot read repository state.' }
git checkout main
if ($LASTEXITCODE -ne 0) { throw 'Cannot check out main.' }
git pull --ff-only
if ($LASTEXITCODE -ne 0) { throw 'Cannot fast-forward main.' }
$head = git rev-parse HEAD
$remote = git ls-remote origin refs/heads/main
if ($LASTEXITCODE -ne 0 -or !$remote -or ($remote -split '\s+')[0] -ne $head) { throw 'Main does not match the remote.' }
if (!(Test-Path -LiteralPath "docs/releases/$tag.md") -or !(Test-Path -LiteralPath LICENSE)) { throw 'Release notes/license missing.' }
$secretNames = gh secret list --json name --jq '.[].name'
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify signing-secret names.' }
foreach ($name in @('ANDROID_KEYSTORE_BASE64','ANDROID_KEYSTORE_PASSWORD','ANDROID_KEY_ALIAS','ANDROID_KEY_PASSWORD')) {
    if ($name -notin $secretNames) { throw "Missing signing secret: $name" }
}
$runs = gh run list --workflow CI --branch main --commit $head --limit 1 --json status,conclusion | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or !$runs -or $runs[0].status -ne 'completed' -or $runs[0].conclusion -ne 'success') { throw 'Main CI must pass before tagging.' }
$remoteTag = git ls-remote origin "refs/tags/$tag"
if ($LASTEXITCODE -ne 0) { throw 'Cannot check remote tags.' }
if ($remoteTag -or (git tag --list $tag)) { throw 'This tag already exists; use a new version.' }
git tag -a $tag -m "Release $tag"
if ($LASTEXITCODE -ne 0) { throw 'Cannot create release tag.' }
git push origin $tag
if ($LASTEXITCODE -ne 0) { throw 'Tag push failed. Inspect the local tag before retrying.' }
Write-Host "Pushed $tag; GitHub Actions will build and publish the signed APK."
} finally { Pop-Location }
