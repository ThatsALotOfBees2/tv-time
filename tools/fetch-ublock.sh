#!/usr/bin/env bash
# Downloads the newest uBlock Origin (Firefox build) and unpacks it into the app's assets,
# where GeckoView loads it as a built-in extension. Run before building the APK.
# Optional: UBLOCK_VERSION=1.62.0 to pin a version. GITHUB_TOKEN avoids API rate limits.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/ublock
AUTH=()
[ -n "${GITHUB_TOKEN:-}" ] && AUTH=(-H "Authorization: Bearer $GITHUB_TOKEN")
if [ -n "${UBLOCK_VERSION:-}" ]; then
  API="https://api.github.com/repos/gorhill/uBlock/releases/tags/$UBLOCK_VERSION"
else
  API="https://api.github.com/repos/gorhill/uBlock/releases/latest"
fi
URL=$(curl -fsSL "${AUTH[@]}" "$API" | python3 -c '
import json, sys
rel = json.load(sys.stdin)
for a in rel["assets"]:
    if a["name"].endswith(".firefox.signed.xpi") or a["name"].endswith(".firefox.xpi"):
        print(a["browser_download_url"]); break
')
[ -n "$URL" ] || { echo "No Firefox build of uBlock Origin found" >&2; exit 1; }
echo "uBlock Origin: $URL"
TMP=$(mktemp -d)
curl -fsSL -o "$TMP/ublock.xpi" "$URL"
rm -rf "$DEST"
mkdir -p "$DEST"
python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$TMP/ublock.xpi" "$DEST"
rm -rf "$DEST/META-INF" "$TMP"
python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print("uBlock Origin", m["version"], "->", sys.argv[1])' "$DEST/manifest.json"
