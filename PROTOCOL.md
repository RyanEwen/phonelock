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

`grant`/`deny` with `req` also tell the other parent's app that the request was answered.

## Phone → parents

| `t` | fields |
|---|---|
| `state` | `grants` {pkg: untilMs}, `home` [pkg], `others` [pkg]?, `labels` {pkg: label}, `parent` |
| `event` | `text` (human readable; e.g. "Dad: WhatsApp allowed until 6:00 PM", "Time's up: WhatsApp") |
| `req` | `req` (id), `app`, `label`, `minutes` — kid tapped "Ask for an app" (rate-limited to 1/min) |
