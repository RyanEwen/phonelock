#!/bin/bash
# Deploy the PhoneLock hub (server/) to the "apps" host, the same way other projects there deploy:
# a git checkout under /home/apps, docker compose on the shared nginx network, and a site file in nginx.
#   tools/deploy-hub.sh            # deploys origin/main
# Needs on the host, once: /home/apps/phonelock/server/.env (from .env.example) and, for push,
# server/secrets/firebase.json. Both are git-ignored and survive the reset below.
# A changed nginx site needs root: run 'sudo apps-sudo-window' on the host first (15 minutes of sudo).
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=${DEPLOY_SSH_HOST:-apps}
DIR=${DEPLOY_REPO_PATH:-/home/apps/phonelock}
REPO=https://github.com/RyanEwen/phonelock.git

# Only deploy what is pushed, so the host matches the repo.
git fetch -q origin main
[ -z "$(git status --porcelain -- server)" ] || { echo "server/ has uncommitted changes"; exit 1; }
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "HEAD is not origin/main; push first"; exit 1; }

# The remote script runs under 'bash -s' with errexit, one command per line, so any failure stops it.
ssh "$HOST" "bash -s -- '$DIR' '$REPO'" <<'REMOTE'
set -euo pipefail
DIR=$1
REPO=$2
if [ ! -d "$DIR/.git" ]; then
  git clone -q "$REPO" "$DIR"
fi
cd "$DIR"
git fetch -q origin main
old_yml=$(sha256sum server/server.yml | cut -c1-64)
git reset -q --hard origin/main
cd server
if [ ! -f .env ]; then
  echo "create $DIR/server/.env from .env.example first"
  exit 1
fi
mkdir -p -m 700 secrets
chmod 700 secrets
domain=$(sed -n 's/^PHONELOCK_DOMAIN=//p' .env)
if [ -z "$domain" ]; then
  echo "PHONELOCK_DOMAIN is empty in .env"
  exit 1
fi

docker compose -f compose.server.yml up -d
# ntfy reads server.yml only at start, and a changed bind-mounted file doesn't recreate the container.
if [ "$old_yml" != "$(sha256sum server.yml | cut -c1-64)" ]; then
  docker compose -f compose.server.yml restart hub
fi

# The shared nginx reads sites from /root/nginx/conf.d (read-only inside the container); install only
# when changed, and put the previous files back if the new config doesn't pass nginx -t.
site=$(mktemp)
sed "s/__DOMAIN__/$domain/" nginx.conf > "$site"
if ! docker exec nginx cat /etc/nginx/conf.d/phonelock.conf 2>/dev/null | cmp -s - "$site" \
  || ! docker exec nginx cat /etc/nginx/conf.d/phonelock.proxy 2>/dev/null | cmp -s - nginx.proxy; then
  backup=$(mktemp -d)
  sudo -n cp -p /root/nginx/conf.d/phonelock.conf /root/nginx/conf.d/phonelock.proxy "$backup/" 2>/dev/null || true
  if ! sudo -n install -m 644 nginx.proxy /root/nginx/conf.d/phonelock.proxy; then
    echo "nginx site changed: run sudo apps-sudo-window on the host, then deploy again"
    exit 1
  fi
  sudo -n install -m 644 "$site" /root/nginx/conf.d/phonelock.conf
  if ! docker exec nginx nginx -t; then
    echo "new nginx config failed its test; restoring the previous one"
    sudo -n rm -f /root/nginx/conf.d/phonelock.conf /root/nginx/conf.d/phonelock.proxy
    sudo -n cp -p "$backup"/phonelock.* /root/nginx/conf.d/ 2>/dev/null || true
    exit 1
  fi
  docker exec nginx nginx -s reload
fi
rm -f "$site"

sleep 3
docker exec phonelock-hub wget -q -O - http://localhost:80/v1/health
REMOTE
