#!/bin/bash
# Publish both APKs as a GitHub release on the (public) project repo. The parent app's setup QR code
# downloads the lock app from its's latest release, so publish after every lock app change.
#   tools/publish-release.sh <version, e.g. 1.1>
set -e
cd "$(dirname "$0")/.."
REPO=RyanEwen/phonelock
v=${1:?version}
lock=lock/build/outputs/apk/release/lock-release.apk
parent=companion/build/outputs/apk/release/companion-release.apk
[ -f "$lock" ] && [ -f "$parent" ] || { echo "build first: ./build.sh"; exit 1; }
gh release create "v$v" --repo "$REPO" --title "PhoneLock $v" \
  --notes "Install PhoneLock (parent app) on each parent's phone. Set up a child's phone from the parent app: Set up a new phone." \
  "$lock" "$parent#PhoneLock-parent.apk"
