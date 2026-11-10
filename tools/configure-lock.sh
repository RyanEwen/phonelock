#!/bin/bash
# Push a device's topic, secret and name to its locked phone over adb.
#   tools/configure-lock.sh <slug> [adb-serial]
# Uses WSL adb; set ADB=/path/to/adb(.exe) to use another (e.g. Windows adb for USB).
set -e
cd "$(dirname "$0")/.."
P=secrets/devices/${1:?slug}/pairing.properties
A=${ADB:-$HOME/android-sdk/platform-tools/adb}
S=${2:+-s $2}
get() { sed -n "s/^$1=//p" "$P"; }
cfg() { $A $S shell "am broadcast -n home.phonelock/.ConfigReceiver -a home.phonelock.CONFIG --es cmd $1 --es value '$2'" | tail -1; }
cfg remote "$(get server)|$(get topic)"
cfg secret "$(get secret)"
cfg name "$(get name)"
