# Parent ⇄ phone protocol

Transport: one private ntfy topic (on ntfy.sh or the PhoneLock hub; on the hub every message is also pushed through Firebase to the FCM topic of the same name, and the phone marks requests, notable events, `loc` and `shot` with ntfy priority 4 for high-priority delivery) (`secrets/pairing.properties`). Every message body is
`base64url(iv[12] ‖ AES-256-GCM(ciphertext ‖ tag))` with AAD `phonelock/1`, keyed by the 32-byte pairing
secret. Anything that doesn't decrypt is ignored, so the topic name alone can't control the phone and
readers of the topic see only ciphertext.

Plaintext is JSON with common fields:

| field | |
|---|---|
| `v` | 1 |
| `t` | message type |
| `id` | random, unique; receivers drop duplicates (replay protection) |
| `ts` | sender clock, ms; the phone drops messages older than 6 h or >5 min in the future |
| `from` | `phone`, or the parent's name |

## Parent → phone

| `t` | fields | effect |
|---|---|---|
| `status` | `full` | phone replies with `state` (`full` adds `others`) |
| `grant` | `app` (package), `minutes` (1–1440), `req`? | allow app until min(now, ts)+minutes; accepted late (up to 24 h) while time remains; ends any block on it |
| `revoke` | `app` or `"all"` | end grant(s) |
| `block` | `app`, `minutes` (1–1440) | take a normally allowed app away until min(now, ts)+minutes; accepted late (up to 24 h) while time remains; ends any grant on it |
| `unblock` | `app` | end a block early |
| `deny` | `req`, `label` | tell the kid a request was declined |
| `home` | `op` (`add`/`remove`), `app` | older form of `appmode`: add = normal + limited hours, remove = 0 |
| `appmode` | `app`, and either `mask` (bits: 1 normal, 2 limited hours, 4 bedtime; 0 = only by grant) or `set`/`clear` (bits changed against the device's current mask) | which situations an app is on the home screen in; non-zero adds it to the home list |
| `schedule` | `rules` [{`mode` (`limited`/`bedtime`), `days` (bit 0 = Monday … bit 6 = Sunday), `start`, `end` (minutes after midnight; `end < start` runs past midnight, `end == start` is all day)}] | replaces the weekly schedule; bedtime wins over limited hours |
| `mode` | `mode` (`limited` / `bedtime` / `none` to end), `minutes` (1–1440) | start limited hours or bedtime by hand until min(now, ts)+minutes; the stricter of it and the schedule applies (bedtime > limited > normal); accepted late while time remains |
| `update` | | check for a lock app update now; a newer verified release installs the next time the screen is off (no call or ring); results as `event`s |
| `uninstall` | `app` | uninstall a removable app (installed on the device, not built in, not the lock app); dropped if more than 30 minutes late; result reported as an `event` and a fresh `state` |
| `offer` | `app`, `on` | put an app on (`true`) or take it off the grantable list; apps taken off also leave the kid's Ask picker; phone replies with `state` (`full`) |
| `icons` | `apps` [pkg] | phone replies with `icons` (`req` = this message's `id`); parents ask only for icons they haven't cached |
| `calls` | `limitOut`, `limitIn`, `safe` [{`name`, `number`}] | call limits and safe list (replaces both); emergency numbers are never limited |
| `calllog` | | phone replies with `calls` (`req` = this message's `id`): up to 200 recent calls |
| `contacts` | `op` (`list` / `add` {`name`, `number`} / `remove` {`key`: the contact's lookup key; dropped if more than 30 minutes late}) | phone replies with `contacts` |
| `files` | `op` (`ls` / `get` / `put` {`name`, with a file attachment} / `rm` / `mkdir` / `mv` {`to`}), `path` (relative to shared storage; `Android/` refused in any letter case; top-level folders can't be deleted; `rm`/`mv`/`put` dropped if more than 30 minutes late; uploads never overwrite, a taken name gets " (1)") | phone replies with `files` (`req`, `error`?, `dir`, `entries`) or, for `get`, `file`; only the requesting parent app acts on these replies |
| `parentmode` | `minutes` (0 ends it, up to 720) | parent mode on the device (Settings and Play Store) until then or until the screen turns off; dropped if more than 5 minutes late |
| `pin` | `pin` (4–8 digits) | sets the parent PIN; never echoed or logged |
| `locate` | | phone replies with `loc` (`req` = this message's `id`) |
| `battery` | `alert` (percent, 0 = off, up to 50) | low-battery alert threshold, shared by all parents; phone replies with `state` (`full`) and an `event` |
| `debug` | `minutes` (0 turns off, up to 240) | recovery: USB and wireless debugging on until then (needs WRITE_SECURE_SETTINGS, granted at setup); dropped if more than 30 minutes late; the reply `event` says where to connect |
| `diag` | | phone replies with `diag` (`req` = this message's `id`); only the asking parent app shows it |
| `track` | `since` (ms) | phone replies with `track` (`req` = this message's `id`) holding every location log entry still running at or after `since` |
| `limits` | `limits` [{`id`, `name` (group name, "" for one app), `apps` [pkg], `min` [7 ints, Sunday first: minutes allowed that day, -1 = no limit]}] | replaces the daily app time limits; apps whose limit ran out leave the home screen until midnight; grant time and parent mode don't count; phone replies with `state` and an `event` |
| `usage` | `since` (`yyyy-MM-dd`), `req` | phone replies with `usage`: its daily record (kept 400 days) from that day on |
| `ring` | `op` (`start`/`stop`), `seconds`? (10–300, default 120) | ring at full alarm volume with a Stop screen; `start` while ringing extends it |
| `screen` | | phone replies with `shot` (`req` = this message's `id`) and always shows the kid "<from> viewed your screen" |

`grant`/`deny` with `req` also tell the other parent's app that the request was answered.

## Phone → parents

| `t` | fields |
|---|---|
| `state` | `name`, `grants` {pkg: untilMs}, `blocks` {pkg: untilMs}, with `full`: `calls` {`limitOut`, `limitIn`, `safe`} and `can` {`files`, `calllog`}, `home` [pkg], `others` [pkg]?, `labels` {pkg: label}, `parent`, `parentUntil` (ms, 0 when off), `ringing`, `version` (lock app versionCode), `updateReady`? (downloaded version waiting for screen-off), `removable` [pkg]? (with `full`: apps that can be uninstalled), `notOffered` [pkg]? (with `full`: apps taken off the grantable list), `mode` (`normal`/`limited`/`bedtime`, schedule and manual combined), `modeUntil` (ms, 0 = no schedule), `manual` {`mode`, `until`}? (a situation started by hand), with `full`: `masks` {pkg: mask}, `schedule` [rule], `battAlert` (low-battery threshold %, 0 = off); always: `batt` (%), `chg` (charging), `limits` [limit], `appTime` {`day`, `apps` {pkg: minutes today}, `used` {limit id: counted minutes}, `out` [limit id], `labels` {pkg: label}}, `screenOn`, `using` {`app`, `since` (ms, when it was opened)}? (the app in front, absent when none) |
| `event` | `text` (human readable; e.g. "Dad: WhatsApp allowed until 6:00 PM", "Time's up: WhatsApp"), `by`? (the parent whose action it was), `quiet`? (routine; list only, no notification). "Battery low: N%" and "Turned on" are kept and retried until sent (a late one says "(at 3:02 PM)"); "Turning off" is best effort, sent while the device shuts down |
| `req` | `req` (id), `app`, `label`, `minutes`; kid tapped "Ask for an app" (rate-limited to 1/min) |
| `loc` | `req`, `lat`, `lng`, `acc` (m), `fix` (ms), `prov`, `batt` (%), `chg`, `error`?; position fields are absent when no fix was available |
| `track` | `points` [{`t` first seen ms, `to` last seen ms, `lat`, `lng`, `acc` m?, `batt`?, `chg`?}], `apps`? [{`app`, `label`, `t`, `to`}] (app use since the last send, sessions of a minute or more), `batt`, `chg`, `req`? (when answering a `track` request). The location log: sent every 15 minutes (a heartbeat even with no new points), entries are keyed by `t` and an entry seen again carries a later `to`. Kept 7 days on the phone; entries taken offline are sent once the hub accepts them (cursor advances only on HTTP 2xx) |
| `diag` | `req`, `checks` [{`name`, `ok`? (absent = information), `value`?}], `log` (the lock app's recent log, up to 60 KB); the hub topic is masked in both, no keys, PIN or contacts |
| `big` | none; its attachment is a whole message (JSON) too large for ntfy's 4 KB limit, handled as if received directly. Parents send `big` the same way (e.g. a long safe list) |
| `calls` | `entries` [{`ts`, `number`, `name`, `type` (in/out/missed/rejected/blocked/voicemail), `dur` s}], `req`?, `error`?; sent on request and as new calls are logged |
| `contacts` | `list` [{`key`, `name`, `numbers` [..]}], `req`, `error`? |
| `files` | `req`, `op`, `path`, `error`?, `dir`, `entries` [{`name`, `dir`, `size`, `mtime`}] |
| `file` | `req`, `path`, `name`, `size`; with an attachment holding the file (at most 48 MB) |
| `icons` | `req`; with an attachment holding JSON {pkg: base64 WebP, 96 px} |
| `usage` | `req`, `days` {`yyyy-MM-dd`: {pkg: minutes}}, `labels` {pkg: label}, `error`? (usage access missing); usually sent as `big` |
| `shot` | `req`, `app`?, `label`?, `error`?; with an attachment holding the screenshot unless `error` is set |

Temporary grants override the schedule, and temporary blocks remove scheduled apps; the latest grant or block on an app replaces the other. When the situation changes the phone re-locks and sends an `event` ("Bedtime until 7:00 AM") and a fresh `state`. Ringing changes are announced with `event` and a fresh `state`. Screenshot views are logged as an `event`, at most once per parent every 5 minutes.

## File attachments (file management)

Files sent either way (`file` replies and `files` `put`) use a pieced format so neither app holds a whole file in memory: records `len[4, big-endian] ‖ iv[12] ‖ AES-256-GCM(piece ‖ tag)`, pieces of at most 1 MiB, AAD `phonelock/file/<message id>/<piece index>/<1 if last piece else 0>`. At most 48 MB of file, 50 MB on the hub.

## Attachments (screenshots, icons)

The phone uploads the screenshot with an ntfy `PUT` (`Filename: blob.bin`) and puts the sealed `shot` message in the `Message` header, so subscribers receive one ntfy message whose `message` is the sealed JSON and whose `attachment.url` points at the file. The file is `iv[12] ‖ AES-256-GCM(JPEG ‖ tag)` (raw bytes, not base64) under the same key with AAD `phonelock/blob/<id of the shot message>`, which binds it to that message. ntfy.sh deletes attachments after about 3 hours. Parents only download from the phone's own ntfy server, and only for requests they sent.
