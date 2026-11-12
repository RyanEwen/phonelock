# PhoneLock privacy policy

Last updated: 11 November 2026

PhoneLock is a parental control app made by Dynamic Solutions ("we"). It has two parts: the **parent app** (on a parent's phone) and the **lock app** (on the child's phone or tablet). This policy explains what information they handle and who can read it.

## The short version

- Information from your child's device goes only to the parents paired with it.
- It is end-to-end encrypted on the devices. We run the relay server that passes messages along, and we cannot read them. Neither can Google's push service.
- One exception: to draw the map and turn positions into street addresses, the parent app asks Google Maps, which then learns roughly which area is being looked at (not whose device it is).
- We don't sell data, show ads or use analytics or tracking SDKs.

## What the apps handle

The lock app sends the following from the child's device to the paired parents:

- **Location**: when a parent asks, and a log taken every 15 minutes (every 2 minutes while moving, plus positions other apps on the device obtain anyway), kept for 7 days.
- **Battery**: level and charging state, and notices when the device turns off or on.
- **Apps**: the installed apps and their names and icons, the child's requests to use them, and which apps were used when and for how long (from Android's own usage record), kept on the parents' phones for 7 days.
- **Connections**: the names of new Wi-Fi networks the child joins and Bluetooth devices they pair.
- **Calls and contacts**: the call log (numbers, names, times, durations) and contacts, so parents can review calls and manage a safe list.
- **Files**: names and contents of files, photos and videos in the device's shared storage, only when a parent browses or downloads them.
- **Screen**: a screenshot of the current screen when a parent taps View screen, or, without a parent asking, when an unfamiliar Google services screen opens on the child's device (at most once a day per screen, until a parent marks it OK or blocks it). The child is notified every time. The parent app keeps these unasked screenshots until a parent deals with them.
- **Diagnostics**: a technical report (settings, permissions and the lock app's own log) when a parent asks for one.

The parent app sends the parent's instructions (for example "allow this app for 30 minutes") and the name the parent chose to show on the child's device.

## Who can read it

Each child device has its own secret key, created at setup and shared only with the parents' apps through the pairing code. Every message, file and screenshot is encrypted with that key (AES-256-GCM) before it leaves the device and decrypted only on the paired devices. Without the key, nobody can read it, including us.

## Servers and services

- **Relay server ("hub")**: messages pass through our self-hosted relay at phonelock.dynamic-solutions.ca. It stores encrypted messages for up to 3 days and encrypted attachments for up to 24 hours so devices that were offline can catch up, then deletes them. Like any web server, it sees the IP address of each connecting device and the random channel name the devices share, and keeps access logs for security and rate limiting for 14 days.
- **Google Firebase Cloud Messaging**: delivers notifications to the parent app. Google receives the same encrypted messages and a device token for the parent's phone, under [Google's privacy policy](https://policies.google.com/privacy).
- **GitHub**: the lock app checks GitHub's public release page for updates and downloads them from there.
- **Address lookup**: the parent app turns locations into street addresses with Android's built-in geocoder, which on most phones is provided by Google, so Google receives those positions (without knowing whose device they came from).
- **Google Maps**: the parent app's map is drawn by the Google Maps SDK, which downloads map images for the area shown, so Google learns roughly which area is being viewed, under [Google's privacy policy](https://policies.google.com/privacy).

## What stays on each device

- **Parent's phone:** its copy of the information above (location history, call log, activity list, settings). Screenshots a parent asks for are kept in memory only and never saved; screenshots of unfamiliar screens are kept until a parent marks them OK, blocks or dismisses them (at most 20 per device).
- **Child's device:** the location log (7 days) and its settings.

Removing a device in the parent app, or uninstalling either app, deletes that app's copy.

## Children

PhoneLock is meant to be set up and used by a parent or legal guardian on a device their child uses. The child is told on their device that location, battery, calls and app use are shared, is notified each time their screen is viewed, and sees a notification that parent controls are active. PhoneLock cannot be hidden from them. We do not collect any information from children for ourselves.

## Your choices

Parents can stop sharing at any time by removing the device from the parent app, or by removing PhoneLock from the child's device [TODO: needs the planned "Remove PhoneLock" option in parent mode; see play/open-questions.md]. Because we cannot read the data, we cannot look it up or hand it over. The relay server deletes everything it holds within 3 days.

## Contact

contact@dynamic-solutions.ca

We'll post changes to this policy here and update the date above.
