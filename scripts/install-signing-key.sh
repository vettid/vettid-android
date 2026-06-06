#!/usr/bin/env bash
# Decrypt a signing bundle produced by package-signing-key.sh and install
# the files into the correct locations in this vettid-android checkout.
#
# Usage:
#   ./scripts/install-signing-key.sh <bundle.tar.gz.enc>

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <bundle.tar.gz.enc>" >&2
  exit 1
fi

BUNDLE="$1"
if [[ ! -f "$BUNDLE" ]]; then
  echo "ERROR: bundle not found: $BUNDLE" >&2
  exit 1
fi

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

# Sanity check: are we in the vettid-android repo?
if [[ ! -f "$REPO_ROOT/app/build.gradle.kts" ]] || ! grep -q "com.vettid.app" "$REPO_ROOT/app/build.gradle.kts"; then
  echo "ERROR: this does not look like the vettid-android repo root" >&2
  echo "       (run from the repo root or via ./scripts/install-signing-key.sh)" >&2
  exit 1
fi

KEYSTORE_DEST="$REPO_ROOT/keystore/release.keystore"
PROPS_DEST="$REPO_ROOT/keystore.properties"

# Verify SHA-256 if the sender provided one.
echo "Bundle SHA-256:"
shasum -a 256 "$BUNDLE"
echo "Compare this against the SHA-256 the sender shared over a separate channel."
read -r -p "Does it match? [y/N] " ok
if [[ "$ok" != "y" && "$ok" != "Y" ]]; then
  echo "Aborted." >&2
  exit 1
fi

# Refuse to clobber existing files without explicit confirmation.
for f in "$KEYSTORE_DEST" "$PROPS_DEST"; do
  if [[ -e "$f" ]]; then
    echo "WARNING: $f already exists."
    read -r -p "Overwrite? [y/N] " ow
    if [[ "$ow" != "y" && "$ow" != "Y" ]]; then
      echo "Aborted." >&2
      exit 1
    fi
  fi
done

read -r -s -p "Enter passphrase: " PASS; echo

TMPDIR_PATH="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_PATH"' EXIT

export VETTID_BUNDLE_PASS="$PASS"
unset PASS

# Decrypt -> untar into a temp dir, then move into place.
if ! openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 \
      -pass env:VETTID_BUNDLE_PASS -in "$BUNDLE" \
      | tar -C "$TMPDIR_PATH" -xzf -; then
  unset VETTID_BUNDLE_PASS
  echo "ERROR: decryption failed (wrong passphrase or corrupted bundle)" >&2
  exit 1
fi
unset VETTID_BUNDLE_PASS

# Verify expected contents.
if [[ ! -f "$TMPDIR_PATH/keystore/release.keystore" || ! -f "$TMPDIR_PATH/keystore.properties" ]]; then
  echo "ERROR: bundle did not contain the expected files" >&2
  exit 1
fi

mkdir -p "$REPO_ROOT/keystore"
mv "$TMPDIR_PATH/keystore/release.keystore" "$KEYSTORE_DEST"
mv "$TMPDIR_PATH/keystore.properties"       "$PROPS_DEST"
chmod 600 "$KEYSTORE_DEST" "$PROPS_DEST"

echo
echo "Installed:"
echo "  $KEYSTORE_DEST"
echo "  $PROPS_DEST"

# Sanity check: the cert SHA-256 in the keystore should match the pin in
# keystore.properties. If keytool isn't on PATH, skip this gracefully.
if command -v keytool >/dev/null 2>&1; then
  STORE_PW="$(grep '^storePassword=' "$PROPS_DEST" | cut -d= -f2-)"
  ALIAS="$(grep '^keyAlias=' "$PROPS_DEST" | cut -d= -f2-)"
  PIN="$(grep '^signingCertSha256=' "$PROPS_DEST" | cut -d= -f2- | tr 'a-f' 'A-F')"
  ACTUAL="$(keytool -list -v -keystore "$KEYSTORE_DEST" -storepass "$STORE_PW" -alias "$ALIAS" 2>/dev/null \
            | awk '/SHA256:/ {print $2; exit}' | tr -d ':' | tr 'a-f' 'A-F')"
  if [[ -n "$PIN" && -n "$ACTUAL" ]]; then
    if [[ "$PIN" == "$ACTUAL" ]]; then
      echo "Cert SHA-256 matches pin: OK"
    else
      echo "WARNING: cert SHA-256 does NOT match signingCertSha256 in keystore.properties" >&2
      echo "  pin:    $PIN" >&2
      echo "  actual: $ACTUAL" >&2
    fi
  fi
else
  echo "(skipped cert-pin verification: keytool not on PATH)"
fi

echo
echo "Done. You can now build a signed release with:"
echo "  ./gradlew assembleRelease"
