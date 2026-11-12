#!/bin/bash
# Build both apps inside WSL.
#   lock/build/outputs/apk/release/lock-release.apk            (child's phone)
#   companion/build/outputs/apk/github/release/companion-github-release.apk  (parents' phones, GitHub build;
#   the Play build is companion:assemblePlayRelease, built only when asked for)
set -e
cd "$(dirname "$0")"
export ANDROID_HOME="$HOME/android-sdk"
export GRADLE_USER_HOME="$HOME/.gradle"
echo "sdk.dir=$ANDROID_HOME" > local.properties
mkdir -p secrets
if [ ! -f secrets/phonelock.keystore ]; then
  # New signing key with a random password, kept next to it in secrets/ (git-ignored).
  pw=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
  keytool -genkeypair -keystore secrets/phonelock.keystore -storepass "$pw" -keypass "$pw" \
    -alias phonelock -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=PhoneLock" >/dev/null
  (umask 077; printf 'storePassword=%s\nkeyPassword=%s\n' "$pw" "$pw" > secrets/signing.properties)
fi
"$HOME/tools/gradle-9.9.1/bin/gradle" --no-daemon -q :lock:assembleRelease :companion:assembleGithubRelease "$@"
ls -l lock/build/outputs/apk/release/*.apk companion/build/outputs/apk/github/release/*.apk
