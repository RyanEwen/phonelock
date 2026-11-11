# Before listing on Google Play

Open items for publishing the parent app. The hub limits, disclosure screen, monitoring declaration, privacy policy draft, data safety answers and listing draft are done; these are not.

## Needs a decision

1. **Play build without the background connection.** The parent app has a foreground service (type `specialUse`) holding a live hub connection, and asks for battery-optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Both are only needed when push isn't available (a self-hosted hub without Firebase). Play restricts both and asks for justification; with push always configured in the Play build, they would likely be refused. Proposal: a `play` build flavor without the service, the permission and the "Allow background use" hint, and the current build (`github` flavor) keeps them.
2. **QR setup code.** QR setup is no longer offered in the app (phone-to-phone setup replaced it), but its code (SetupPhoneScreen, Setup.provisioningJson, the lock app's ProvisioningActivity) is still there. Delete it, or keep it for later.
3. **Removing PhoneLock.** The lock app can already give up device owner (`ConfigReceiver` `release`, over adb), but parents have no button for it. Play and the privacy policy expect a way out. Proposal: "Remove PhoneLock from this device…" in the parent app's device menu (over the hub, or over Wireless debugging like setup), which releases and uninstalls.
4. **Placeholders in PRIVACY.md:** organization name, contact email, date, and how long the shared nginx keeps access logs on the apps host.

## Needs doing

- **Reviewer access.** A setup and feature video, and possibly a demo device reviewers can pair with.
- **Store assets.** Icon, feature graphic, phone screenshots.
- **First release on GitHub,** after a build passes testing on a real child device (needed for the lock app download and the updater).
