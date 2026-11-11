# PhoneLock

Two Android apps for locking down a child's phone:

- **lock/** – device-owner app installed on the child's phone. It is the home screen, keeps the phone in
  lock-task (kiosk) mode with an allowlist of apps, hides everything else, and applies user restrictions
  (no SMS, no installs from unknown sources, no account changes, no factory reset, no safe mode, …).
  Parents can grant apps temporarily over [ntfy](https://ntfy.sh).
- **companion/** – parent app: sets up and pairs child phones, answers requests, grants and revokes apps, finds, rings and views the phone.

## Hub (server)

Both apps talk through an [ntfy](https://ntfy.sh) server. `server/` runs our own (ntfy in Docker behind the apps host's shared nginx, Cloudflare-proxied) with Firebase Cloud Messaging, so parent phones get messages by push instead of a permanent background connection; see `server/README.md`. The parent app reads the hub address and Firebase settings from `secrets/firebase.properties` (`hubUrl`, `appId`, `apiKey`, `projectId`, `senderId`); without it, it uses ntfy.sh and its own connection. Only the parent app is meant for Google Play; the lock app installs through the setup QR code.

## Build (WSL)

```bash
./build.sh            # -> lock/build/outputs/apk/release/lock-release.apk
```

Needs JDK 17, Android SDK (`~/android-sdk`, platform 36, build-tools 36) and Gradle 8.11.1 in `~/tools`.
Signing key and secrets live in `secrets/` (git-ignored). Keep `secrets/phonelock.keystore` safe: updates must be
signed with the same key.

## Set up a child's phone without a PC

In the parent app, tap **Set up a new phone**, enter the child's phone name, your name and a parent PIN (and optionally Wi-Fi), then follow the steps it shows: factory reset the child's phone, tap its first welcome screen 6 times to open Android's setup QR scanner, and scan the code. Android downloads the lock app from the public release (`Setup.LOCK_APK_URL`), checks it is signed with our key, makes it device owner and hands it the pairing and PIN, so the phone starts locked and already connected to that parent's app. USB debugging stays off. The second parent adds the phone from the first parent's app: phone settings > **Share with another parent**.

Afterwards, in parent mode on the child's phone (hold the clock 4 s, enter the PIN), add their Google account and turn on Settings > Accessibility > Parent screen view.

Publishing a release (both APKs, to the GitHub releases the setup code downloads from):

```bash
tools/publish-release.sh <version>
```

## Install on the child's phone with adb

The phone must have **no accounts** when the device owner is set (add them back afterwards).

```bash
adb install lock-release.apk
adb shell dpm set-device-owner home.phonelock/.AdminReceiver
```

Then pair it and switch on screen view and ringing through Do Not Disturb (needs USB debugging, so do it before `debug off`):

```bash
tools/configure-lock.sh <slug> [adb-serial]
```

Parents can then find the phone (opens Google Maps), ring it at full volume and view its screen from the parent app. The kid gets a notice whenever a parent views the screen, both parents' activity logs record every view, and location is kept switched on.

## adb control

```bash
adb shell am broadcast -n home.phonelock/.ConfigReceiver -a home.phonelock.CONFIG --es cmd <cmd> --es value <value>
```

| cmd | value | |
|---|---|---|
| `status` | | current state |
| `pin` | digits | parent PIN (hold the clock 4 s on the home screen) |
| `apps` | `pkg1,pkg2,…` | home-screen apps, in order |
| `support` | `pkg,…` | allowed inside the lock but not shown |
| `hide` | `pkg,…` | always hidden |
| `parent` | minutes | parent mode (Settings + Play Store unlocked) |
| `lock` | | end parent mode |
| `remote` | topic or `server\|topic` | ntfy topic for remote commands |
| `say` | command text | run a remote command locally |
| `dns` | hostname or empty | lock Private DNS |
| `debug` | `on`/`off` | keep USB debugging while locked |
| `release` | | remove all policy and stop being device owner |
