#!/bin/bash
# Create (once) the shared pairing secret and print the pairing link + QR code for the parent apps.
#   tools/make-pairing.sh          -> secrets/pairing.properties, secrets/pairing-qr.png
# Then on the child's phone (adb):
#   am broadcast -n home.phonelock/.ConfigReceiver -a home.phonelock.CONFIG --es cmd remote --es value <topic>
#   am broadcast -n home.phonelock/.ConfigReceiver -a home.phonelock.CONFIG --es cmd secret --es value <secret>
set -e
cd "$(dirname "$0")/.."
mkdir -p secrets
P=secrets/pairing.properties
if ! grep -q '^secret=.\{40,\}' "$P" 2>/dev/null; then
  topic=$(tr -d '\r\n\357\273\277' < secrets/remote-topic.txt)
  secret=$(java tools/ParentCli.java newsecret)
  printf 'server=https://ntfy.sh\ntopic=%s\nsecret=%s\n' "$topic" "$secret" > "$P"
  chmod 600 "$P"
fi
server=$(sed -n 's/^server=//p' "$P")
topic=$(sed -n 's/^topic=//p' "$P")
secret=$(sed -n 's/^secret=//p' "$P")
link="phonelock://pair?s=${server}&t=${topic}&k=${secret}"
echo "$link" > secrets/pairing-link.txt
qrencode -o secrets/pairing-qr.png -s 10 -m 2 "$link"
chmod 600 secrets/*
echo "wrote secrets/pairing.properties, secrets/pairing-link.txt, secrets/pairing-qr.png"
