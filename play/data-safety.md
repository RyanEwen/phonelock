# Google Play Data safety answers (parent app)

## Basis

Google doesn't count data as "collected" when it leaves the device only end-to-end encrypted, unreadable by the developer or any intermediary, with only the sender and recipient holding keys ([Google: Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)). Every PhoneLock message, file and screenshot is encrypted on the child's or parent's device with a key the relay server never has (see PROTOCOL.md), so the child-device data shown in the app is not "collected".

What does leave the device readable to a service is the push token, through the Firebase Cloud Messaging SDK.

## Answers

- **Does your app collect or share any of the required user data types?** Yes
- **Is all of the user data collected by your app encrypted in transit?** Yes
- **Do you provide a way for users to request that their data is deleted?** Yes. Removing the device or uninstalling deletes it on the phone; the relay deletes everything within 24 hours.

### Data types

| Type | Collected | Shared | Optional | Purpose |
|---|---|---|---|---|
| Device or other IDs (Firebase installation ID and push token) | Yes | No (Google processes it as our service provider) | No | App functionality (notifications) |

Location: the child's positions are end-to-end encrypted (not collected), but the parent app sends map areas and positions to Google Maps and the Android geocoder to draw maps and addresses. Check Google's Maps SDK guidance for whether this must be declared as "Approximate location, shared with Google, app functionality"; declaring it is the safe choice.

Not collected: personal info, financial info, health, messages, photos and videos, audio, files, calendar, contacts, app activity, web browsing, app info and performance. The child-device data the app shows is end-to-end encrypted (see Basis).

## Other declarations

- **Monitoring app (Stalkerware policy):** the manifest declares `isMonitoringTool` = `child_monitoring`, and the listing presents it as parental control only. The child is told on their device and it can't be hidden.
- **Prominent disclosure:** the first-run "Before you start" screen (Disclosure.kt), before any device is added.
- **Camera:** scanning pairing QR codes.
- **Foreground service (specialUse) and REQUEST_IGNORE_BATTERY_OPTIMIZATIONS:** see play/open-questions.md; likely to be removed from the Play build.
