#!/bin/bash
# Build debug APK and create GitHub Release
# Keeps only the 2 most recent releases on GitHub

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
RELEASES_DIR="$PROJECT_DIR/releases"
REPO="mesmerverse/vettid-android"

cd "$PROJECT_DIR"

echo "Building debug APK..."
./gradlew assembleDebug

# Create local releases directory
mkdir -p "$RELEASES_DIR"

# Generate version tag
VERSION="v$(date +%Y%m%d-%H%M%S)"
APK_NAME="vettid-app-debug.apk"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/$APK_NAME"

echo ""
echo "Creating GitHub Release: $VERSION"

# Create release and upload APK
gh release create "$VERSION" \
    "$APK_PATH" \
    --repo "$REPO" \
    --title "Debug Build $VERSION" \
    --notes "Automated debug build

Built: $(date '+%Y-%m-%d %H:%M:%S')

🤖 Generated with [Claude Code](https://claude.ai/claude-code)"

echo ""
echo "Cleaning up old releases (keeping 2 most recent)..."

# Get list of releases sorted by date, skip the 2 most recent
OLD_RELEASES=$(gh release list --repo "$REPO" --limit 100 | tail -n +3 | awk '{print $1}')

for release in $OLD_RELEASES; do
    echo "Deleting old release: $release"
    gh release delete "$release" --repo "$REPO" --yes --cleanup-tag
done

echo ""
echo "Current releases:"
gh release list --repo "$REPO" --limit 5

echo ""
echo "Build complete!"
echo "Download URL: https://github.com/$REPO/releases/tag/$VERSION"
