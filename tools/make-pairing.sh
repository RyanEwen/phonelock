#!/bin/bash
# Create (once per locked phone) its ntfy topic + shared secret, and the pairing link/QR for the parent apps.
#   tools/make-pairing.sh <slug> "<Phone name>"
#   -> secrets/devices/<slug>/{pairing.properties,pairing-link.txt,pairing-qr.png}
# Then on the locked phone (adb), set the same values:
#   tools/configure-lock.sh <slug> [adb-serial]
set -e
cd "$(dirname "$0")/.."
slug=${1:?slug, e.g. kid}
name=${2:-Phone}
D=secrets/devices/$slug
mkdir -p "$D"
P=$D/pairing.properties
if ! grep -q '^secret=.\{40,\}' "$P" 2>/dev/null; then
  topic=pl-$(head -c 15 /dev/urandom | base32 | tr -d '=' | tr 'A-Z' 'a-z')
  secret=$(java tools/ParentCli.java newsecret)
  printf 'server=https://ntfy.sh\ntopic=%s\nsecret=%s\n' "$topic" "$secret" > "$P"
fi
grep -q '^name=' "$P" && sed -i "s/^name=.*/name=$name/" "$P" || echo "name=$name" >> "$P"
server=$(sed -n 's/^server=//p' "$P")
topic=$(sed -n 's/^topic=//p' "$P")
secret=$(sed -n 's/^secret=//p' "$P")
enc_name=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$name")
link="phonelock://pair?s=${server}&t=${topic}&k=${secret}&n=${enc_name}"
echo "$link" > "$D/pairing-link.txt"
qrencode -o "$D/pairing-qr.png" -s 10 -m 2 "$link"
chmod 600 "$D"/*
echo "wrote $D/{pairing.properties,pairing-link.txt,pairing-qr.png}"
