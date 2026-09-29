#!/usr/bin/env bash
set -euo pipefail

REPO_NAME="${1:-android-art-optimizer}"
VISIBILITY="${VISIBILITY:-private}"

gh auth status >/dev/null
LOGIN=$(gh api user -q .login)
NAME=$(gh api user -q '.name // .login')

if [ ! -d .git ]; then
  git init -b main
fi
if ! git rev-parse --verify HEAD >/dev/null 2>&1; then
  git branch -M main
  git config user.name "${NAME}"
  git config user.email "${LOGIN}@users.noreply.github.com"
  git add .
  git commit -m "chore: bootstrap Android ART Optimizer"
fi

if ! git remote get-url origin >/dev/null 2>&1; then
  gh repo create "$REPO_NAME" --"$VISIBILITY" --source . --remote origin --push 
else
  git push -u origin main
fi

REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)

gh label create "implementation" --repo "$REPO" --description "Primary implementation work" --color 1D76DB --force >/dev/null
gh label create "android" --repo "$REPO" --description "Android application work" --color 3DDC84 --force >/dev/null
gh label create "security" --repo "$REPO" --description "Security-sensitive behavior" --color B60205 --force >/dev/null

if ! gh issue list --repo "$REPO" --search '"Implement v1 Android ART Optimizer" in:title' --json title -q '.[].title' | grep -Fxq "Implement v1 Android ART Optimizer"; then
  gh issue create \
    --repo "$REPO" \
    --title "Implement v1 Android ART Optimizer" \
    --body-file docs/IMPLEMENTATION_ISSUE.md \
    --label implementation \
    --label android \
    --label security
fi

echo "Repository: https://github.com/$REPO"
gh issue list --repo "$REPO" --search '"Implement v1 Android ART Optimizer" in:title' --limit 1
