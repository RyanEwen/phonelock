#!/bin/bash
# Publish both APKs as a GitHub release on this (public) repo. The parent app's phone-to-phone setup
# installs the lock app from the latest release, and installed apps update themselves from it, using
# lock-version.json and parent-version.json to decide.
# Anything published reaches children's devices unattended: only publish a build already tested on a device
# over adb (a lock app that crashes on start can't be fixed remotely).
#   tools/publish-release.sh            # builds from the committed, pushed HEAD
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=RyanEwen/phonelock
[ -z "$(git status --porcelain)" ] || { echo "commit first: releases must match a pushed commit"; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "push first"; exit 1; }

# Memory-capped, single worker with in-process Kotlin: the settings that build reliably on this machine
# alongside other work (larger parallel builds have been OOM-killed).
systemd-run --user --scope -q -p MemoryMax=3500M -p MemorySwapMax=0 env GRADLE_OPTS=-Xmx1600m \
  bash ./build.sh --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
lock=lock/build/outputs/apk/release/lock-release.apk
parent=companion/build/outputs/apk/github/release/companion-github-release.apk
version=$(git rev-list --count HEAD)
out=$(mktemp -d)
cp "$parent" "$out/PhoneLock-parent.apk"
# Each app checks its own version file before downloading: the lock app lock-version.json, the parent app
# (GitHub build) parent-version.json.
version_file() { printf '{"versionCode":%s,"sha256":"%s","size":%s}\n' "$version" \
  "$(sha256sum "$1" | cut -c1-64)" "$(stat -c %s "$1")"; }
version_file "$lock" > "$out/lock-version.json"
version_file "$parent" > "$out/parent-version.json"

gh release create "v1.0.$version" --repo "$REPO" --title "PhoneLock 1.0.$version" --latest \
  --notes "Lock app installs from the parent app's \"Set up a new device\" and updates itself. Parent app: PhoneLock-parent.apk (updates itself from here once installed)." \
  "$lock" "$out/lock-version.json" "$out/PhoneLock-parent.apk" "$out/parent-version.json"
rm -rf "$out"
