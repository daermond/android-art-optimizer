#!/usr/bin/env bash
set -euo pipefail
VERSION="${1:?Usage: scripts/create-release.sh 0.1.0}"
TAG="v${VERSION#v}"

test -z "$(git status --porcelain)" || { echo "Working tree is not clean" >&2; exit 1; }
git checkout main
git pull --ff-only
git tag -a "$TAG" -m "Release $TAG"
git push origin "$TAG"
echo "Pushed $TAG; GitHub Actions will build and publish the signed APK."
