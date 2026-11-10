#!/bin/bash
# Build both apps inside WSL.
#   lock/build/outputs/apk/release/lock-release.apk            (child's phone)
#   companion/build/outputs/apk/release/companion-release.apk  (parents' phones)
set -e
cd "$(dirname "$0")"
export ANDROID_HOME="$HOME/android-sdk"
export GRADLE_USER_HOME="$HOME/.gradle"
echo "sdk.dir=$ANDROID_HOME" > local.properties
mkdir -p secrets
if [ ! -f secrets/phonelock.keystore ]; then
  keytool -genkeypair -keystore secrets/phonelock.keystore -storepass phonelock -keypass phonelock \
    -alias phonelock -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=PhoneLock" >/dev/null
fi
"$HOME/tools/gradle-8.11.1/bin/gradle" --no-daemon -q assembleRelease "$@"
ls -l lock/build/outputs/apk/release/*.apk companion/build/outputs/apk/release/*.apk
