#!/bin/bash
# Build production debug APK, install on device, and create GitHub Release
# Keeps only the 3 most recent releases on GitHub

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
REPO="vettid/vettid-android"

cd "$PROJECT_DIR"

# Get git info for release notes
GIT_SHA=$(git rev-parse --short HEAD)
GIT_MSG=$(git log -1 --pretty=%s)

echo "Building production debug APK..."
./gradlew assembleProductionDebug

APK_PATH="$PROJECT_DIR/app/build/outputs/apk/production/debug/vettid-app-production-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: APK not found at $APK_PATH"
    echo "Searching for APK..."
    find "$PROJECT_DIR/app/build/outputs/apk" -name "*.apk" -type f 2>/dev/null
    exit 1
fi

# Install on connected device if available
if adb devices | grep -q "device$"; then
    echo ""
    echo "Installing on device..."
    adb install -r "$APK_PATH"
    echo "Installed successfully. Launching app..."
    adb shell am start -n com.vettid.app/.MainActivity
else
    echo ""
    echo "No device connected — skipping install"
fi

# Create GitHub Release
VERSION="v$(date +%Y%m%d-%H%M%S)"

echo ""
echo "Creating GitHub Release: $VERSION"

gh release create "$VERSION" \
    "$APK_PATH#vettid-production-debug.apk" \
    --repo "$REPO" \
    --title "Debug Build $VERSION" \
    --notes "Production debug build

Commit: $GIT_SHA — $GIT_MSG
Built: $(date '+%Y-%m-%d %H:%M:%S')"

echo ""
echo "Cleaning up old releases (keeping 3 most recent)..."

# Get list of releases sorted by date, skip the 3 most recent
OLD_RELEASES=$(gh release list --repo "$REPO" --limit 100 | tail -n +4 | awk '{print $1}')

for release in $OLD_RELEASES; do
    echo "Deleting old release: $release"
    gh release delete "$release" --repo "$REPO" --yes --cleanup-tag
done

echo ""
echo "Current releases:"
gh release list --repo "$REPO" --limit 5

echo ""
echo "Done! Release: https://github.com/$REPO/releases/tag/$VERSION"
