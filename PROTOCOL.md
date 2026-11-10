# Parent ⇄ phone protocol

Transport: one private ntfy topic (`secrets/pairing.properties`). Every message body is
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
| `grant` | `app` (package), `minutes` (1–1440), `req`? | allow app until now+minutes |
| `revoke` | `app` or `"all"` | end grant(s) |
| `deny` | `req`, `label` | tell the kid a request was declined |
| `home` | `op` (`add`/`remove`), `app` | change permanent home-screen apps |
| `locate` | | phone replies with `loc` (`req` = this message's `id`) |
| `ring` | `op` (`start`/`stop`), `seconds`? (10–300, default 120) | ring at full alarm volume with a Stop screen; `start` while ringing extends it |
| `screen` | `notify`? (default true) | phone replies with `shot` (`req` = this message's `id`); with `notify` it shows the kid "<from> viewed your screen" |

`grant`/`deny` with `req` also tell the other parent's app that the request was answered.

## Phone → parents

| `t` | fields |
|---|---|
| `state` | `grants` {pkg: untilMs}, `home` [pkg], `others` [pkg]?, `labels` {pkg: label}, `parent`, `ringing` |
| `event` | `text` (human readable; e.g. "Dad: WhatsApp allowed until 6:00 PM", "Time's up: WhatsApp") |
| `req` | `req` (id), `app`, `label`, `minutes` — kid tapped "Ask for an app" (rate-limited to 1/min) |
| `loc` | `req`, `lat`, `lng`, `acc` (m), `fix` (ms), `prov`, `batt` (%), `chg`, `error`?; position fields are absent when no fix was available |
| `shot` | `req`, `app`?, `label`?, `error`?; with an attachment holding the screenshot unless `error` is set |

Ringing changes are announced with `event` and a fresh `state`. Screenshot views are always logged as an `event` (noting when the kid wasn't told), at most once per parent every 5 minutes.

## Attachments (screenshots)

The phone uploads the screenshot with an ntfy `PUT` (`Filename: blob.bin`) and puts the sealed `shot` message in the `Message` header, so subscribers receive one ntfy message whose `message` is the sealed JSON and whose `attachment.url` points at the file. The file is `iv[12] ‖ AES-256-GCM(JPEG ‖ tag)` (raw bytes, not base64) under the same key with AAD `phonelock/blob/<id of the shot message>`, which binds it to that message. ntfy.sh deletes attachments after about 3 hours. Parents only download from the phone's own ntfy server, and only for requests they sent.
