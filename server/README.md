# PhoneLock hub

A self-hosted [ntfy](https://ntfy.sh) server that both apps talk through, with Firebase Cloud Messaging so parent phones get messages without a permanent notification. Everything it relays is already encrypted by the apps.

## Where it runs

On the "apps" host (`ssh apps`), like the other projects there: a git checkout at `/home/apps/phonelock`, the `phonelock-hub` container on the shared `nginx_default` network (no published ports), and a site file in the shared `nginx` container. DNS is a proxied (orange-cloud) Cloudflare record, so Cloudflare provides HTTPS.

## First time

1. Cloudflare: proxied `A` record for the hub name (`phonelock.dynamic-solutions.ca`) pointing at the apps host.
2. On the host: `git clone https://github.com/RyanEwen/phonelock.git /home/apps/phonelock`, then create `server/.env` from `.env.example`.
3. Firebase (for push): project with an Android app `home.phonelock.parent`; save the service-account key as `server/secrets/firebase.json` on the host and set `NTFY_FIREBASE_KEY_FILE` in `.env`. Put the app's `appId`, `apiKey`, `projectId`, `senderId` and the hub's `hubUrl` in `secrets/firebase.properties` in the repo root for the parent app build.

## Deploy

```bash
tools/deploy-hub.sh
curl https://<hub name>/v1/health
```

The script deploys the pushed `main`, starts the container, installs the nginx site and checks health.
