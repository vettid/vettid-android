#!/usr/bin/env python3
"""
VettID Android App Icon Generator
Requires: Pillow (pip install Pillow)

Usage: python generate-icons.py [source-image]
Default source: ../app/src/main/assets/vettid-icon-300.png
"""

import sys
import os
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("Pillow not found. Install with: pip install Pillow")
    sys.exit(1)

# Android icon specifications: (directory, size)
ANDROID_ICONS = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]

def generate_icons(source_path: str, res_dir: str, assets_dir: str):
    """Generate all Android app icon sizes from source image."""

    # Load source image
    print(f"Loading source image: {source_path}")
    img = Image.open(source_path)

    # Convert to RGBA if needed
    if img.mode != 'RGBA':
        img = img.convert('RGBA')

    print(f"Generating icons in: {res_dir}")

    for directory, size in ANDROID_ICONS:
        output_dir = os.path.join(res_dir, directory)
        os.makedirs(output_dir, exist_ok=True)

        # High-quality resize using LANCZOS
        resized = img.resize((size, size), Image.LANCZOS)

        # Save launcher icons
        for name in ["ic_launcher.png", "ic_launcher_round.png"]:
            output_path = os.path.join(output_dir, name)
            resized.save(output_path, 'PNG', optimize=True)

        print(f"  Created: {directory}/ic_launcher.png ({size}x{size})")

    # Generate Play Store icon (512x512)
    os.makedirs(assets_dir, exist_ok=True)
    playstore_icon = img.resize((512, 512), Image.LANCZOS)
    playstore_path = os.path.join(assets_dir, "ic_launcher-playstore.png")
    playstore_icon.save(playstore_path, 'PNG', optimize=True)
    print(f"  Created: ic_launcher-playstore.png (512x512)")

    print(f"\nDone! Generated {len(ANDROID_ICONS) * 2 + 1} icons.")

def main():
    # Default paths
    script_dir = Path(__file__).parent
    source = sys.argv[1] if len(sys.argv) > 1 else str(script_dir.parent / "app" / "src" / "main" / "assets" / "vettid-icon-300.png")
    res_dir = str(script_dir.parent / "app" / "src" / "main" / "res")
    assets_dir = str(script_dir.parent / "app" / "src" / "main" / "assets")

    if not os.path.exists(source):
        print(f"Error: Source image not found: {source}")
        print("Please provide a high-resolution PNG image (512x512 or larger)")
        sys.exit(1)

    generate_icons(source, res_dir, assets_dir)

if __name__ == "__main__":
    main()
