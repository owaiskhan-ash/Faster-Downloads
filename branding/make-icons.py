#!/usr/bin/env python3
"""Regenerates the Android launcher icons from `branding/app-icon.png`.

The source art is black glyphs on a neon field. Android does not want a flat PNG for the
adaptive icon: it wants the *foreground* only, transparent, inside the 66 dp safe zone
(the launcher masks and parallaxes it), plus a background. So we rebuild the alpha channel
from luminance, scale the glyph into the safe circle, and let the launcher supply the neon.

Legacy raster icons (mdpi..xxxhdpi) are composed flat, because pre-Android-8 launchers
have no adaptive support.

Usage:  python branding/make-icons.py      (needs Pillow)
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
SOURCE = ROOT / "branding" / "app-icon.png"

NEON = (204, 255, 0)   # #CCFF00
INK = (10, 10, 10)     # #0A0A0A
GLYPH_DP = 250         # glyph width inside the 108 dp adaptive canvas (safe circle = 66 dp)
LEGACY_RATIO = 0.64    # glyph width relative to the icon size for flat mipmaps

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def luma(pixel):
    r, g, b = pixel[:3]
    return (r * 299 + g * 587 + b * 114) // 1000


def glyph_mask(source):
    """Crops to the glyph and returns a hard-edged alpha mask of it."""
    width, height = source.size
    pixels = source.load()
    min_x, min_y, max_x, max_y = width, height, -1, -1
    for y in range(height):
        for x in range(width):
            if luma(pixels[x, y]) < 110:
                min_x, min_y = min(min_x, x), min(min_y, y)
                max_x, max_y = max(max_x, x), max(max_y, y)
    pad = 6
    box = (
        max(0, min_x - pad),
        max(0, min_y - pad),
        min(width, max_x + 1 + pad),
        min(height, max_y + 1 + pad),
    )
    crop = source.crop(box)
    crop_pixels = crop.load()
    mask = Image.new("L", crop.size)
    mask_pixels = mask.load()
    # Anything lighter than the ink is background; the soft ramp only anti-aliases the
    # stroke edges. Without the hard cut below 150 the source's grain leaks through as a
    # faint rectangular box behind the glyphs.
    for y in range(crop.height):
        for x in range(crop.width):
            value = luma(crop_pixels[x, y])
            if value >= 150:
                mask_pixels[x, y] = 0
            else:
                mask_pixels[x, y] = int(round(255 * min(1.0, (150 - value) / 120.0)))
    return mask


def glyph_layer(mask, target_width):
    width = max(1, int(round(target_width)))
    height = max(1, int(round(target_width * mask.height / mask.width)))
    layer = Image.new("RGBA", (width, height), INK + (0,))
    layer.putalpha(mask.resize((width, height), Image.LANCZOS))
    return layer


def centred(canvas, layer):
    canvas.alpha_composite(layer, ((canvas.width - layer.width) // 2, (canvas.height - layer.height) // 2))
    return canvas


def flat_icon(mask, size):
    canvas = Image.new("RGBA", (size, size), NEON + (255,))
    return centred(canvas, glyph_layer(mask, size * LEGACY_RATIO)).convert("RGB")


def main():
    source = Image.open(SOURCE).convert("RGB")
    mask = glyph_mask(source)

    # Adaptive foreground: transparent, glyph inside the safe zone.
    (RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
    foreground = Image.new("RGBA", (432, 432), INK + (0,))
    centred(foreground, glyph_layer(mask, GLYPH_DP)).save(
        RES / "drawable-nodpi" / "ic_launcher_foreground.png", optimize=True
    )

    for density, size in DENSITIES.items():
        icon = flat_icon(mask, size)
        (RES / f"mipmap-{density}").mkdir(parents=True, exist_ok=True)
        icon.save(RES / f"mipmap-{density}" / "ic_launcher.png", optimize=True)
        icon.save(RES / f"mipmap-{density}" / "ic_launcher_round.png", optimize=True)

    flat_icon(mask, 512).save(ROOT / "branding" / "app-icon-512.png", optimize=True)
    print(f"wrote foreground + {len(DENSITIES)} densities from {SOURCE.name}")


if __name__ == "__main__":
    main()
