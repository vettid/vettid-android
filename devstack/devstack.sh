#!/usr/bin/env bash
# The local dev stack for the Android app (ANDROID-PLAN A2; see README.md):
# vettid-vault's integration stack (real vettid-relay, LocalStack, parent,
# dev enclave, member API stand-in) plus a vaultctl peer vault, reachable
# from a USB-connected phone through `adb reverse`.
#
#   devstack/devstack.sh up       # build and start; prints the ready file
#   devstack/devstack.sh status
#   devstack/devstack.sh down     # stop everything, remove the adb reverse rules
#
# Environment:
#   VAULT_DIR      vettid-vault checkout (default: ../vettid-vault next to this repo)
#   VAULT_REF      commit or tag of vettid-vault to run (default: HEAD of VAULT_DIR)
#   ANDROID_SERIAL the phone to set up adb reverse on (default: the only device)
#   DEVSTACK_SKIP_MEMCHECK=1  skip the free-memory check (shared machine rule: >= 8 GB)
#
# The vettid-vault checkout is never modified: a snapshot of VAULT_REF is
# extracted into the cache directory and the runner (go/devstack_test.go)
# is copied into the snapshot.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
VAULT_DIR="${VAULT_DIR:-$REPO/../vettid-vault}"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/vettid-android-devstack"
STATE="$CACHE/run"
PORTS=(18080 18081 18082)
PROJECT=vettid-android-devstack
LOCALSTACK=http://127.0.0.1:4566

compose() { if command -v podman >/dev/null 2>&1; then podman compose -p "$PROJECT" "$@"; else docker compose -p "$PROJECT" "$@"; fi; }

memcheck() {
  [[ "${DEVSTACK_SKIP_MEMCHECK:-}" == 1 ]] && return 0
  while :; do
    avail=$(free -g | awk '/^Mem:/ {print $7}')
    if (( avail >= 8 )); then return 0; fi
    echo "only ${avail} GB available (need 8); waiting 60 s" >&2
    sleep 60
  done
}

snapshot() {
  local ref commit
  ref="${VAULT_REF:-HEAD}"
  commit="$(git -C "$VAULT_DIR" rev-parse --verify "$ref^{commit}")"
  SNAP="$CACHE/vault-$commit"
  if [[ ! -f "$SNAP/go.mod" ]]; then
    mkdir -p "$SNAP"
    git -C "$VAULT_DIR" archive "$commit" | tar -x -C "$SNAP"
  fi
  mkdir -p "$SNAP/devstack/android"
  cp "$HERE/go/devstack_test.go" "$SNAP/devstack/android/devstack_test.go"
  echo "vettid-vault $commit" >&2
}

reverse() {
  if ! command -v adb >/dev/null 2>&1; then return 0; fi
  if [[ -z "${ANDROID_SERIAL:-}" ]] && [[ "$(adb devices | grep -c 'device$' || true)" != 1 ]]; then
    echo "no single adb device; set ANDROID_SERIAL and run: adb reverse tcp:<port> tcp:<port> for ${PORTS[*]}" >&2
    return 0
  fi
  for p in "${PORTS[@]}"; do adb reverse "tcp:$p" "tcp:$p" >/dev/null; done
  echo "adb reverse: ${PORTS[*]}" >&2
}

up() {
  if [[ -f "$STATE/pid" ]] && kill -0 "$(cat "$STATE/pid")" 2>/dev/null; then
    echo "already running (pid $(cat "$STATE/pid"))"; reverse; cat "$STATE/ready.json" 2>/dev/null || true; return 0
  fi
  memcheck
  snapshot
  rm -rf "$STATE"; mkdir -p "$STATE/logs"
  compose -f "$SNAP/integration/docker-compose.yml" up -d >&2
  for _ in $(seq 1 90); do curl -sf "$LOCALSTACK/_localstack/health" >/dev/null && break; sleep 2; done
  curl -sf "$LOCALSTACK/_localstack/health" >/dev/null || { echo "LocalStack did not start" >&2; exit 1; }
  echo "$SNAP" > "$STATE/snapshot"
  (
    cd "$SNAP"
    export VAULT_IT_LOCALSTACK="$LOCALSTACK" DEVSTACK_READY_FILE="$STATE/ready.json" DEVSTACK_STOP_FILE="$STATE/stop" \
      DEVSTACK_LOG_DIR="$STATE/logs" GOMAXPROCS=2 GOFLAGS=-p=1
    nohup go test -count=1 -p 1 -v -timeout 0 -tags 'devenclave integration androiddevstack' -run TestDevStack ./devstack/android/ \
      >"$STATE/logs/devstack.log" 2>&1 &
    echo $! > "$STATE/pid"
  )
  echo "starting (log: $STATE/logs/devstack.log)" >&2
  for _ in $(seq 1 600); do
    [[ -f "$STATE/ready.json" ]] && break
    if ! kill -0 "$(cat "$STATE/pid")" 2>/dev/null; then echo "the stack exited; see $STATE/logs/devstack.log" >&2; tail -40 "$STATE/logs/devstack.log" >&2; down; exit 1; fi
    sleep 1
  done
  [[ -f "$STATE/ready.json" ]] || { echo "timed out; see $STATE/logs/devstack.log" >&2; exit 1; }
  reverse
  cat "$STATE/ready.json"
}

down() {
  if [[ -f "$STATE/pid" ]]; then
    local pid; pid="$(cat "$STATE/pid")"
    touch "$STATE/stop"
    for _ in $(seq 1 120); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill "$pid" 2>/dev/null || true
    rm -f "$STATE/pid"
  fi
  local snap; snap="$(cat "$STATE/snapshot" 2>/dev/null || true)"
  if [[ -n "$snap" && -f "$snap/integration/docker-compose.yml" ]]; then
    compose -f "$snap/integration/docker-compose.yml" down >&2 || true
  fi
  if command -v adb >/dev/null 2>&1; then for p in "${PORTS[@]}"; do adb reverse --remove "tcp:$p" >/dev/null 2>&1 || true; done; fi
  echo "stopped" >&2
}

status() {
  if [[ -f "$STATE/pid" ]] && kill -0 "$(cat "$STATE/pid")" 2>/dev/null; then
    echo "running (pid $(cat "$STATE/pid"))"; cat "$STATE/ready.json" 2>/dev/null || echo "(not ready yet)"
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
