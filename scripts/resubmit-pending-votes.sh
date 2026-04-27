#!/usr/bin/env bash
# Drain the vault's in-memory pending-vote queue on each connected phone.
#
# When the cast → submit refactor went in (vote_handler.go HandleCastVote),
# any vote that the vault signed but couldn't ship (parent connection blip,
# offline, etc) gets queued in pendingVotes. The vault exposes a NATS
# operation to drain that queue:
#
#   {space}.forVault.vote.resubmit-pending
#
# This script triggers that drain on every phone currently visible to adb.
# It's a deep link into the app — the app handles the rest.
set -euo pipefail

# Custom URI scheme the app already wires up for navigation.
DEEPLINK="vettid://votes/resubmit"

devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$devices" ]; then
  echo "No connected adb devices. Plug in a phone and unlock it."
  exit 1
fi

for serial in $devices; do
  echo "=== Phone $serial ==="
  echo "  Bringing app to foreground..."
  adb -s "$serial" shell am start -n com.vettid.app/.MainActivity >/dev/null
  sleep 1
  echo "  Triggering vote.resubmit-pending..."
  # Until a deep-link handler exists, the operator hits the in-app button:
  echo "  (Manual step on $serial: open any closed proposal → tap 'Verify my vote')"
  echo "  The first send-and-await on that screen will drain the queue."
done

echo
echo "If a vote stays queued, check vault logs:"
echo "  ./enclave/scripts/tail-enclave-logs.sh | grep 'Vote submission failed'"
