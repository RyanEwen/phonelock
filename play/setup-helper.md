# Setup helper: plan

**Decided (Nov 2026):** option A, built into the parent app (ChildSetup.kt, WifiSetupScreen.kt) with our own adb client (companion `adb/` package: pairing, SPAKE2 ported from BoringSSL, connection, shell, install, discovery). No third-party adb library: Kadb was tried, but its pairing dependency is GPL-3.0, which can't ship alongside Google's proprietary libraries. No cables, no web pages. QR setup is no longer offered.

Goal: a parent sets up a child's device with the full feature set without knowing adb, without a factory reset (when the device has no accounts signed in), and ideally without a computer.

## What setup has to do

The same steps as `tools/configure-lock.sh` and the README's adb section, driven over adb:

1. Check the device: Android version, and that no accounts are signed in (`dumpsys account`). Becoming device owner refuses otherwise.
2. Install the lock app (the signed release APK).
3. `dpm set-device-owner home.phonelock/.AdminReceiver`.
4. Pairing: the parent app makes a new topic and key, and hands them over (the `remote`, `secret` and `name` config broadcasts) along with the parent PIN.
5. One-time grants: screen view (accessibility), call screening and redirection roles, call log, all-files access, Do Not Disturb access, Doze exemption.
6. Check everything with the same checks as Diagnostics, then switch wireless debugging and developer options back off.

## Options

### A. Phone to phone over Wi-Fi (recommended)

The parent app talks adb to the child's device using Android's own Wireless debugging (Android 11 and later; PhoneLock needs Android 13), on the same Wi-Fi network.

- **Child device:** turn on developer options (tap Build number 7 times), then Wireless debugging > Pair device with pairing code.
- **Parent app:** "Set up a child's device", enter the code and address shown, and it does steps 1 to 6 with progress and plain-language errors.
- **Pros:** no computer, no cable, full feature set, and pairing is created right there.
- **Cons:** child device must run Android 13+, and both devices must be on the same network.
- **Library needed (third-party, needs approval):** an adb client with wireless pairing, e.g. [Kadb](https://klibs.io/project/flyfishxu/Kadb) (Kotlin), [libadb-android](https://github.com/MuntashirAkon/libadb-android/) or dadb-android. Pairing uses TLS through Conscrypt.
- **Play risk:** an app that installs an APK on another device over adb is unusual. The parent app wouldn't run the code itself, but where the APK comes from matters. Downloading it from GitHub at setup is cleaner than bundling it. Worth checking with Play policy before relying on this in the Play build; the GitHub build can ship it regardless.

### B. Phone to phone with a USB cable

Same as A, but over USB OTG, with the parent's phone as USB host.

- **Pros:** works on child devices older than Android 13 (not supported by the lock app anyway).
- **Cons:** needs a USB-C to USB-C cable or OTG adapter, phones don't always agree which one is host, the child must still allow USB debugging, and fewer adb libraries support USB on Android.
- **Verdict:** only if A turns out to miss devices we care about.

### C. Web page with WebUSB (fallback for computers)

A setup page served from the hub domain that talks adb over USB from Chrome or Edge, using [Tango (ya-webadb)](https://github.com/yume-chan/ya-webadb) (MIT).

- **Pros:** works on Windows, Mac, Linux and ChromeOS, with nothing to install, and on older Android versions.
- **Cons:** needs a computer and cable. Some phones need their maker's USB driver on Windows (e.g. Samsung). The APK must be served from our own domain, because GitHub downloads can't be fetched cross-origin by a web page.

### D. Windows app

A desktop program bundling adb.

- **Cons:** Windows only, needs code signing to avoid SmartScreen warnings, adb redistribution terms to check, and more to maintain than C for the same result.
- **Verdict:** not recommended; C covers the same people.

## Recommendation

1. Build **A** in the parent app first: it's the best experience and covers almost every current device.
2. Add **C** later as the fallback for Android 10 and older, or when the two devices can't share a network.
3. Skip B and D unless real devices need them.

QR setup has since been removed (Nov 2026), and the Play listing can say "set up from your phone, no computer needed" for Android 13+ child devices.

## Open questions

- Approval of the adb client library for option A.
- Whether the Play build may include the setup (policy check), or only the GitHub build.
- Devices with accounts already signed in: guide the parent to remove them temporarily (and re-add them in parent mode afterwards), as was done for the first device.
