#!/usr/bin/env bash
set -euo pipefail
KEYSTORE="${1:?Usage: scripts/setup-github-signing.sh path/to/release.keystore key-alias}"
ALIAS="${2:?Usage: scripts/setup-github-signing.sh path/to/release.keystore key-alias}"

test -f "$KEYSTORE"
gh auth status >/dev/null
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)

read -rsp "Keystore password: " STORE_PASSWORD; echo
read -rsp "Key password: " KEY_PASSWORD; echo

base64 < "$KEYSTORE" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set ANDROID_KEY_ALIAS --repo "$REPO"
printf '%s' "$KEY_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD --repo "$REPO"

unset STORE_PASSWORD KEY_PASSWORD
echo "Signing secrets configured for $REPO"
