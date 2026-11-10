#!/bin/bash
# Deploy the PhoneLock hub (server/) to the "apps" host, the same way other projects there deploy:
# a git checkout under /home/apps, docker compose on the shared nginx network, and a site file in nginx.
#   tools/deploy-hub.sh            # deploys origin/main
# Needs on the host, once: /home/apps/phonelock/server/.env (from .env.example) and, for push,
# server/secrets/firebase.json. Both are git-ignored and survive the reset below.
set -e
cd "$(dirname "$0")/.."
HOST=${DEPLOY_SSH_HOST:-apps}
DIR=${DEPLOY_REPO_PATH:-/home/apps/phonelock}
REPO=https://github.com/RyanEwen/phonelock.git

# Only deploy what is pushed, so the host matches the repo.
git fetch -q origin main
[ -z "$(git status --porcelain -- server)" ] || { echo "server/ has uncommitted changes"; exit 1; }
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "HEAD is not origin/main; push first"; exit 1; }

ssh "$HOST" "set -e
  [ -d '$DIR/.git' ] || git clone -q '$REPO' '$DIR'
  cd '$DIR' && git fetch -q origin main && git reset -q --hard origin/main
  cd server
  [ -f .env ] || { echo 'create $DIR/server/.env from .env.example first'; exit 1; }
  mkdir -p secrets
  docker compose -f compose.server.yml up -d
  domain=\$(sed -n 's/^PHONELOCK_DOMAIN=//p' .env)
  sed \"s/__DOMAIN__/\$domain/\" nginx.conf > /tmp/phonelock.conf
  docker cp /tmp/phonelock.conf nginx:/etc/nginx/conf.d/phonelock.conf
  docker exec nginx nginx -t
  docker exec nginx nginx -s reload
  sleep 3
  docker exec phonelock-hub wget -q -O - http://localhost:80/v1/health"
