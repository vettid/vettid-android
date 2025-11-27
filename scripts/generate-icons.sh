#!/bin/bash
# VettID Android App Icon Generator
# Requires: ImageMagick (brew install imagemagick / apt install imagemagick)
#
# Usage: ./generate-icons.sh [source-image]
# Default source: ../app/src/main/assets/vettid-icon-300.png

set -e

SOURCE="${1:-../app/src/main/assets/vettid-icon-300.png}"
RES_DIR="../app/src/main/res"

if [ ! -f "$SOURCE" ]; then
    echo "Source image not found: $SOURCE"
    echo "Please provide a high-resolution PNG image (512x512 or larger)"
    exit 1
fi

echo "Generating Android app icons from $SOURCE..."

# Legacy launcher icons (for pre-API 26 devices)
# mdpi: 48x48
convert "$SOURCE" -resize 48x48 "$RES_DIR/mipmap-mdpi/ic_launcher.png"
convert "$SOURCE" -resize 48x48 "$RES_DIR/mipmap-mdpi/ic_launcher_round.png"

# hdpi: 72x72
convert "$SOURCE" -resize 72x72 "$RES_DIR/mipmap-hdpi/ic_launcher.png"
convert "$SOURCE" -resize 72x72 "$RES_DIR/mipmap-hdpi/ic_launcher_round.png"

# xhdpi: 96x96
convert "$SOURCE" -resize 96x96 "$RES_DIR/mipmap-xhdpi/ic_launcher.png"
convert "$SOURCE" -resize 96x96 "$RES_DIR/mipmap-xhdpi/ic_launcher_round.png"

# xxhdpi: 144x144
convert "$SOURCE" -resize 144x144 "$RES_DIR/mipmap-xxhdpi/ic_launcher.png"
convert "$SOURCE" -resize 144x144 "$RES_DIR/mipmap-xxhdpi/ic_launcher_round.png"

# xxxhdpi: 192x192
convert "$SOURCE" -resize 192x192 "$RES_DIR/mipmap-xxxhdpi/ic_launcher.png"
convert "$SOURCE" -resize 192x192 "$RES_DIR/mipmap-xxxhdpi/ic_launcher_round.png"

# Play Store icon: 512x512
convert "$SOURCE" -resize 512x512 "$RES_DIR/../assets/ic_launcher-playstore.png"

echo "Done! Generated icons in mipmap directories"
echo ""
echo "Icon sizes generated:"
find "$RES_DIR/mipmap-*" -name "ic_launcher*.png" -exec ls -la {} \;
