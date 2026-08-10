#!/usr/bin/env python3
"""Generate pre-Android 8 launcher icons from the Saba artwork using Pillow."""

from pathlib import Path

from PIL import Image


REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE = REPO_ROOT / "app/src/main/res/drawable-nodpi/saba.webp"
RES_DIR = REPO_ROOT / "app/src/main/res"
LANCZOS = getattr(Image, "Resampling", Image).LANCZOS
# Keep the composition aligned with mipmap-anydpi-v26/ic_launcher_saba.xml.
INSET = 0.18
ICON_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def main() -> None:
    with Image.open(SOURCE) as source_image:
        source = source_image.convert("RGB")

    if source.width != source.height:
        raise ValueError(f"Expected square launcher artwork, got {source.size}")

    for density, icon_size in ICON_SIZES.items():
        inset_pixels = int(icon_size * INSET)
        artwork_size = icon_size - 2 * inset_pixels
        artwork = source.resize(
            (artwork_size, artwork_size),
            LANCZOS,
        )
        icon = Image.new("RGB", (icon_size, icon_size), "white")
        offset = (inset_pixels, inset_pixels)
        icon.paste(artwork, offset)

        output = RES_DIR / f"mipmap-{density}/ic_launcher_saba.png"
        output.parent.mkdir(parents=True, exist_ok=True)
        icon.save(output, format="PNG", optimize=True)
        print(f"Generated {output.relative_to(REPO_ROOT)} ({icon_size}x{icon_size})")


if __name__ == "__main__":
    main()
