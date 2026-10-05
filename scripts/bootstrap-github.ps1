param(
    [string]$RepoName = "android-art-optimizer",
    [ValidateSet("private", "public", "internal")]
    [string]$Visibility = "private"
)
$ErrorActionPreference = "Stop"

gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI is not authenticated." }
$login = gh api user -q .login
$name = gh api user -q '.name // .login'

if (-not (Test-Path .git)) {
    git init -b main
}
$headCommit = git rev-parse --verify HEAD 2>$null
if ($LASTEXITCODE -ne 0) {
    git branch -M main
    git config user.name $name
    git config user.email "$login@users.noreply.github.com"
    git add .
    git commit -m "chore: bootstrap Android ART Optimizer"
}

$origin = git remote get-url origin 2>$null
if (-not $origin) {
    gh repo create $RepoName "--$Visibility" --source . --remote origin --push
} else {
    git push -u origin main
}
if ($LASTEXITCODE -ne 0) { throw "Repository create/push failed." }

$repo = gh repo view --json nameWithOwner -q .nameWithOwner

gh label create implementation --repo $repo --description "Primary implementation work" --color 1D76DB --force | Out-Null
gh label create android --repo $repo --description "Android application work" --color 3DDC84 --force | Out-Null
gh label create security --repo $repo --description "Security-sensitive behavior" --color B60205 --force | Out-Null

$existing = gh issue list --repo $repo --search '"Implement v1 Android ART Optimizer" in:title' --json title -q '.[].title'
if ($existing -notcontains "Implement v1 Android ART Optimizer") {
    gh issue create --repo $repo --title "Implement v1 Android ART Optimizer" --body-file docs/IMPLEMENTATION_ISSUE.md --label implementation --label android --label security
}

Write-Host "Repository: https://github.com/$repo"
gh issue list --repo $repo --search '"Implement v1 Android ART Optimizer" in:title' --limit 1
