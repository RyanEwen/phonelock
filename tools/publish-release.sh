#!/bin/bash
# Publish both APKs as a GitHub release on this (public) repo. QR setup installs the lock app from the
# latest release, and installed lock apps update themselves from it, using lock-version.json to decide.
# Anything published reaches children's devices unattended: only publish a build already tested on a device
# over adb (a lock app that crashes on start can't be fixed remotely).
#   tools/publish-release.sh            # builds from the committed, pushed HEAD
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=RyanEwen/phonelock
[ -z "$(git status --porcelain)" ] || { echo "commit first: releases must match a pushed commit"; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "push first"; exit 1; }

# Same memory cap as other Android builds on this machine.
systemd-run --user --scope -q -p MemoryMax=6G -p MemorySwapMax=0 bash ./build.sh
lock=lock/build/outputs/apk/release/lock-release.apk
parent=companion/build/outputs/apk/release/companion-release.apk
version=$(git rev-list --count HEAD)
out=$(mktemp -d)
cp "$parent" "$out/PhoneLock-parent.apk"
printf '{"versionCode":%s,"sha256":"%s","size":%s}\n' "$version" \
  "$(sha256sum "$lock" | cut -c1-64)" "$(stat -c %s "$lock")" > "$out/lock-version.json"

gh release create "v1.0.$version" --repo "$REPO" --title "PhoneLock 1.0.$version" --latest \
  --notes "Lock app installs through the parent app's setup QR code and updates itself. Parent app: PhoneLock-parent.apk." \
  "$lock" "$out/lock-version.json" "$out/PhoneLock-parent.apk"
rm -rf "$out"
