#!/bin/bash
# Build debug APK and manage releases
# Keeps only the 2 most recent APK versions

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
RELEASES_DIR="$PROJECT_DIR/releases"

cd "$PROJECT_DIR"

echo "Building debug APK..."
./gradlew assembleDebug

# Create releases directory if it doesn't exist
mkdir -p "$RELEASES_DIR"

# Copy APK with timestamp version
VERSION=$(date +%Y%m%d-%H%M%S)
APK_NAME="vettid-app-debug-${VERSION}.apk"
cp "$PROJECT_DIR/app/build/outputs/apk/debug/vettid-app-debug.apk" "$RELEASES_DIR/$APK_NAME"

echo "Created: $RELEASES_DIR/$APK_NAME"

# Cleanup: keep only the 2 most recent APKs
echo "Cleaning up old releases (keeping 2 most recent)..."
cd "$RELEASES_DIR"
ls -t *.apk 2>/dev/null | tail -n +3 | while read -r file; do
    echo "Removing: $file"
    rm -f "$file"
done

echo ""
echo "Current releases:"
ls -lh "$RELEASES_DIR"/*.apk 2>/dev/null || echo "No APKs found"

echo ""
echo "Build complete!"
