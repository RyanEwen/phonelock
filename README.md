# PhoneLock

Two Android apps for locking down a child's phone:

- **lock/** – device-owner app installed on the child's phone. It is the home screen, keeps the phone in
  lock-task (kiosk) mode with an allowlist of apps, hides everything else, and applies user restrictions
  (no SMS, no installs from unknown sources, no account changes, no factory reset, no safe mode, …).
  Parents can grant apps temporarily over [ntfy](https://ntfy.sh).
- **companion/** – parent app (planned) for granting/revoking apps, seeing status and answering requests.

## Build (WSL)

```bash
./build.sh            # -> lock/build/outputs/apk/release/lock-release.apk
```

Needs JDK 17, Android SDK (`~/android-sdk`, platform 36, build-tools 36) and Gradle 8.11.1 in `~/tools`.
Signing key and secrets live in `secrets/` (git-ignored). Keep `secrets/phonelock.keystore` safe: updates must be
signed with the same key.

## Install on the child's phone

The phone must have **no accounts** when the device owner is set (add them back afterwards).

```bash
adb install -t lock-release.apk
adb shell dpm set-device-owner home.phonelock/.AdminReceiver
```

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
