#!/usr/bin/env bash
set -euo pipefail
VERSION="${1:?Usage: scripts/create-release.sh 0.1.0}"
TAG="v${VERSION#v}"
cd "$(dirname "$0")/.."
python3 scripts/release_version.py "$TAG" >/dev/null

test -z "$(git status --porcelain)" || { echo "Working tree is not clean" >&2; exit 1; }
git checkout main
git pull --ff-only
HEAD_SHA=$(git rev-parse HEAD)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
test "$HEAD_SHA" = "$REMOTE_SHA" || { echo 'Main differs from remote' >&2; exit 1; }
test -f "docs/releases/$TAG.md" && test -f LICENSE
SECRET_NAMES=$(gh secret list --json name --jq '.[].name')
for NAME in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  grep -Fxq "$NAME" <<< "$SECRET_NAMES" || { echo "Missing signing secret: $NAME" >&2; exit 1; }
done
CI_STATE=$(gh run list --workflow CI --branch main --commit "$HEAD_SHA" --limit 1 --json status,conclusion --jq '.[0] | .status + ":" + .conclusion')
test "$CI_STATE" = 'completed:success' || { echo 'Main CI must pass before tagging' >&2; exit 1; }
test -z "$(git tag --list "$TAG")" && test -z "$(git ls-remote origin "refs/tags/$TAG")" || { echo 'Tag already exists' >&2; exit 1; }
git tag -a "$TAG" -m "Release $TAG"
git push origin "$TAG"
echo "Pushed $TAG; GitHub Actions will build and publish the signed APK."
