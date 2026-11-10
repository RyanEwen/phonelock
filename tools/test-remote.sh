#!/bin/bash
# Smoke test of the encrypted protocol against the child's phone.
#   tools/test-remote.sh status|grant <pkg> <min>|revoke <pkg>|read
cd "$(dirname "$0")/.."
cli="java tools/ParentCli.java"
case "$1" in
  status) $cli send '{"t":"status","full":true}' ;;
  grant)  $cli send "{\"t\":\"grant\",\"app\":\"$2\",\"minutes\":${3:-2}}" ;;
  revoke) $cli send "{\"t\":\"revoke\",\"app\":\"$2\"}" ;;
  read)   $cli read "${2:-2m}" ;;
esac
