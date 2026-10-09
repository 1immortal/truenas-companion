#!/usr/bin/env python3
"""Builds the README images in docs/images from the screenshot-test renders (example data only).

Run after `./gradlew testDebugUnitTest -Pscreenshots`:  python3 tools/readme/make_readme_images.py
Needs Pillow. Fonts: Inter (falls back to DejaVu Sans).
"""
import os, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SHOTS = os.path.join(ROOT, "screenshots")
OUT = os.path.join(ROOT, "docs", "images")
FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/sand-box/google/Inter/Inter-VariableFont_opsz,wght.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def font(size, weight="Bold"):
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            f = ImageFont.truetype(p, size)
            try:
                f.set_variation_by_name(weight)
            except Exception:
                pass
            return f
    return ImageFont.load_default()


def rounded(im, r):
    m = Image.new("L", im.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, im.width - 1, im.height - 1], r, fill=255)
    im = im.convert("RGBA")
    im.putalpha(m)
    return im


def phone(name, width=540, crop_h=2400):
    """Screen render → rounded phone-screen image `width` px wide (tall renders are cropped to the phone height)."""
    im = Image.open(os.path.join(SHOTS, name)).convert("RGB")
    if im.height > crop_h * im.width // 1080:
        im = im.crop((0, 0, im.width, crop_h * im.width // 1080))
    im = im.resize((width, im.height * width // im.width), Image.LANCZOS)
    return rounded(im, width // 14)


def save(im, name):
    im.save(os.path.join(OUT, name), optimize=True)
    print("wrote", os.path.join("docs/images", name), im.size)


def hero():
    W, H = 1720, 860
    bg = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(bg)
    top, bottom = (0x14, 0x22, 0x5A), (0x07, 0x0D, 0x26)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    # soft glow behind the phones
    glow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glow).ellipse([980, 120, 1640, 780], fill=120)
    glow = glow.filter(ImageFilter.GaussianBlur(120))
    bg.paste(Image.new("RGB", (W, H), (0x2F, 0x5B, 0xEA)), (0, 0), glow)
    canvas = bg.convert("RGBA")

    icon = Image.open(os.path.join(SHOTS, "v180_icon_512.png")).convert("RGBA").resize((200, 200), Image.LANCZOS)
    icon = rounded(icon, 56)  # launcher-style squircle, with a thin light rim so it lifts off the navy
    rim = Image.new("RGBA", (208, 208), (0, 0, 0, 0))
    ImageDraw.Draw(rim).rounded_rectangle([0, 0, 207, 207], 60, fill=(0x6F, 0x8C, 0xFF, 90))
    canvas.alpha_composite(rim, (106, 166))
    canvas.alpha_composite(icon, (110, 170))
    d = ImageDraw.Draw(canvas)
    d.text((104, 400), "YTN", font=font(132), fill="white")
    d.text((110, 560), "Your TrueNAS companion", font=font(50, "SemiBold"), fill=(0x9F, 0xE8, 0xFF))
    d.text((110, 632), "A free, ad-free Android app for", font=font(34, "Regular"), fill=(0xC9, 0xD3, 0xF2))
    d.text((110, 678), "managing TrueNAS SCALE.", font=font(34, "Regular"), fill=(0xC9, 0xD3, 0xF2))

    def shadowed(im, xy):
        sh = Image.new("RGBA", (im.width + 80, im.height + 80), (0, 0, 0, 0))
        sh.paste((0, 0, 0, 150), (40, 50), im)
        sh = sh.filter(ImageFilter.GaussianBlur(22))
        canvas.alpha_composite(sh, (xy[0] - 40, xy[1] - 40))
        canvas.alpha_composite(im, xy)

    light = phone("v180_home_light.png", 330)
    dark = phone("v180_home_dark.png", 350)
    shadowed(light, (1290, 110))
    shadowed(dark, (960, 60))
    save(canvas.convert("RGB"), "hero.png")


def main():
    if not os.path.exists(os.path.join(SHOTS, "v180_home_dark.png")):
        sys.exit("Render the screenshots first: ./gradlew testDebugUnitTest -Pscreenshots")
    os.makedirs(OUT, exist_ok=True)
    tour = {
        "home-dark.png": "v180_home_dark.png",
        "home-light.png": "v180_home_light.png",
        "storage-light.png": "v180_storage_light.png",
        "alerts-dark.png": "v180_alerts_dark.png",
        "system-dark.png": "v180_system_hub_dark.png",
        "protection-dark.png": "v160_protection.png",
        "catalog-dark.png": "preview-catalog-dark.png",
        "editor-dark.png": "v180_smb_editor_dark.png",
        "services-dark.png": "v140_services.png",
        "scheduled-tasks-dark.png": "v140_cron_jobs.png",
        "connection-dark.png": "v180_connection_dark.png",
        "notif-mock-dark.png": "preview-notif-mock-dark.png",
    }
    for out, src in tour.items():
        save(phone(src), out)
    w = Image.open(os.path.join(SHOTS, "v180_widget.png")).convert("RGB")
    save(w.resize((w.width // 2, w.height // 2), Image.LANCZOS), "widget.png")
    i = Image.open(os.path.join(SHOTS, "v180_icon_512.png"))
    save(i, "ytn-icon-512.png")
    save(i, "icon.png")
    hero()


if __name__ == "__main__":
    main()
