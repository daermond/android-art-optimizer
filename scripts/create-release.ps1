param([Parameter(Mandatory=$true)][string]$Version)
$ErrorActionPreference = "Stop"
$tag = "v" + $Version.TrimStart('v')
if (git status --porcelain) { throw "Working tree is not clean." }
git checkout main
git pull --ff-only
git tag -a $tag -m "Release $tag"
git push origin $tag
Write-Host "Pushed $tag; GitHub Actions will build and publish the signed APK."
