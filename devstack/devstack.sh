#!/usr/bin/env bash
# The local dev stack for the Android app (see README.md): vettid-vault's
# `cmd/devstack` (real vettid-relay, LocalStack, parent, dev enclave, member
# API stand-in, vaultctl peer), run by module version with the dev device
# policy of this directory, and reachable from a USB-connected phone through
# `adb reverse`.
#
#   devstack/devstack.sh up       # start; prints ready.json
#   devstack/devstack.sh status
#   devstack/devstack.sh down     # stop everything, remove the adb reverse rules
#
# Environment:
#   VAULT_REF       vettid-vault commit to run (default below)
#   VAULT_SRC       run from this vettid-vault checkout instead (go run ./cmd/devstack there)
#   DEVICE_POLICY   dev device policy file (default: device-policy.json next to this script)
#   ANDROID_SERIAL  the phone(s) to set up adb reverse on, space-separated (default: every connected device)
#   DEVSTACK_SKIP_MEMCHECK=1  skip the free-memory check (shared machine rule: >= 8 GB)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VAULT_REF="${VAULT_REF:-19c0315}"
DEVICE_POLICY="${DEVICE_POLICY:-$HERE/device-policy.json}"
DATA="${XDG_CACHE_HOME:-$HOME/.cache}/vettid-devstack"
STATE="${XDG_CACHE_HOME:-$HOME/.cache}/vettid-android-devstack"
PORTS=(18080 18081 18082)
CTL=http://127.0.0.1:18082

memcheck() {
  [[ "${DEVSTACK_SKIP_MEMCHECK:-}" == 1 ]] && return 0
  while :; do
    avail=$(free -g | awk '/^Mem:/ {print $7}')
    if (( avail >= 8 )); then return 0; fi
    echo "only ${avail} GB available (need 8); waiting 60 s" >&2
    sleep 60
  done
}

serials() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then echo "$ANDROID_SERIAL"; return; fi
  adb devices | awk 'NR > 1 && $2 == "device" {print $1}'
}

reverse() {
  if ! command -v adb >/dev/null 2>&1; then return 0; fi
  local any=0
  for s in $(serials); do
    for p in "${PORTS[@]}"; do adb -s "$s" reverse "tcp:$p" "tcp:$p" >/dev/null; done
    echo "adb reverse on $s: ${PORTS[*]}" >&2
    any=1
  done
  (( any )) || echo "no adb device; run: adb -s <serial> reverse tcp:<port> tcp:<port> for ${PORTS[*]}" >&2
}

running() { [[ -f "$STATE/pid" ]] && kill -0 "$(cat "$STATE/pid")" 2>/dev/null; }

up() {
  if running; then
    echo "already running (pid $(cat "$STATE/pid"))"; reverse; curl -sf "$CTL/dev/info" || true; return 0
  fi
  memcheck
  mkdir -p "$STATE"
  rm -f "$DATA/run/ready.json"
  local policy; policy="$(cd "$(dirname "$DEVICE_POLICY")" && pwd)/$(basename "$DEVICE_POLICY")"
  (
    export GOMAXPROCS=2 GOFLAGS=-p=2
    if [[ -n "${VAULT_SRC:-}" ]]; then
      cd "$VAULT_SRC"
      nohup go run -tags devenclave ./cmd/devstack -dev-device-policy "$policy" -data "$DATA" -mem-wait 10m \
        >"$STATE/devstack.log" 2>&1 &
    else
      nohup go run -tags devenclave "github.com/vettid/vettid-vault/cmd/devstack@$VAULT_REF" -dev-device-policy "$policy" \
        -data "$DATA" -mem-wait 10m >"$STATE/devstack.log" 2>&1 &
    fi
    echo $! > "$STATE/pid"
  )
  echo "starting vettid-vault ${VAULT_SRC:-@$VAULT_REF} (log: $STATE/devstack.log)" >&2
  for _ in $(seq 1 900); do
    [[ -f "$DATA/run/ready.json" ]] && break
    if ! running; then echo "the stack exited; see $STATE/devstack.log" >&2; tail -40 "$STATE/devstack.log" >&2; exit 1; fi
    sleep 1
  done
  [[ -f "$DATA/run/ready.json" ]] || { echo "timed out; see $STATE/devstack.log" >&2; down; exit 1; }
  reverse
  cat "$DATA/run/ready.json"
}

down() {
  if [[ -f "$STATE/pid" ]]; then
    local pid; pid="$(cat "$STATE/pid")"
    # `go run` does not forward SIGTERM; devstack stops by itself once its go parent is gone.
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 120); do curl -sf "$CTL/dev/health" >/dev/null 2>&1 || break; sleep 1; done
    rm -f "$STATE/pid"
  fi
  if command -v adb >/dev/null 2>&1; then
    for s in $(serials); do for p in "${PORTS[@]}"; do adb -s "$s" reverse --remove "tcp:$p" >/dev/null 2>&1 || true; done; done
  fi
  echo "stopped" >&2
}

status() {
  if running; then
    echo "running (pid $(cat "$STATE/pid"))"; curl -sf "$CTL/dev/info" || echo "(not ready yet)"
  else
    echo "not running"
  fi
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  status) status ;;
  *) echo "usage: $0 up|down|status" >&2; exit 2 ;;
esac
