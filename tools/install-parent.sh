#!/bin/bash
# Install the parent app on a phone connected to WSL adb, optionally opening a device's pairing link.
#   tools/install-parent.sh <adb-serial> [slug]
set -e
cd "$(dirname "$0")/.."
A=~/android-sdk/platform-tools/adb
S=${1:?adb serial, e.g. 192.168.1.20:37000}
$A -s "$S" install -r companion/build/outputs/apk/github/release/companion-github-release.apk
if [ -n "$2" ]; then
  link=$(cat "secrets/devices/$2/pairing-link.txt")
  $A -s "$S" shell "am start -a android.intent.action.VIEW -d '$link' home.phonelock.parent" >/dev/null
fi
