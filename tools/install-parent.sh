#!/bin/bash
# Install the parent app on a phone connected to WSL adb and open its pairing screen with the link prefilled.
#   tools/install-parent.sh <adb-serial>
set -e
cd "$(dirname "$0")/.."
A=~/android-sdk/platform-tools/adb
S=${1:?adb serial, e.g. 192.168.1.20:37000}
$A -s "$S" install -r companion/build/outputs/apk/release/companion-release.apk
link=$(cat secrets/pairing-link.txt)
# Quote for the device shell so & survives.
$A -s "$S" shell "am start -a android.intent.action.VIEW -d '$link' home.phonelock.parent"
