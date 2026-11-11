#!/bin/bash
# Push a device's topic, secret and name to its locked phone over adb, and switch on screen view and ring.
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
# Screen view: enable the screenshot accessibility service (the device owner allows only this one).
$A $S shell "settings put secure enabled_accessibility_services home.phonelock/home.phonelock.ScreenService; settings put secure accessibility_enabled 1"
# Ring: Do Not Disturb access, so a ring can get through "Total silence".
$A $S shell cmd notification allow_dnd home.phonelock
# Calls: see outgoing calls (safe list) and screen incoming ones; read the call log for parents.
$A $S shell cmd role add-role-holder android.app.role.CALL_REDIRECTION home.phonelock
$A $S shell cmd role add-role-holder android.app.role.CALL_SCREENING home.phonelock
$A $S shell pm grant home.phonelock android.permission.READ_CALL_LOG || true
# Files: "All files access" for parent file management.
$A $S shell appops set home.phonelock MANAGE_EXTERNAL_STORAGE allow
