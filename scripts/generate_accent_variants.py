#!/usr/bin/env python3
"""Single source for accent color calculations and day/night palette overlays.

Run this script after editing values[-night]/pc_item_colors.xml. Runtime card
geometry remains in the original drawable XMLs; only palette attributes vary.
Use --check in CI to detect stale generated resources. No build-time Python dependency.
"""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
BRAND_H = 345.0           # 品牌粉色相锚点
CHROMA_SCALE = 0.8        # 色度缩放：等 C 下暖橙/蓝紫感知更艳，缩 0.8 对齐品牌粉的柔和感
ACCENT_ALPHAS = (10, 20, 27, 40, 50, 80)


# ---------- sRGB <-> CIELAB (D65) ----------
def _srgb_to_linear(c):
    c /= 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _linear_to_srgb(v):
    v = max(0.0, min(1.0, v))
    return round(255 * (12.92 * v if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055))


def srgb_to_lab(hexv):
    r, g, b = (_srgb_to_linear(int(hexv[i:i + 2], 16)) for i in (3, 5, 7))
    X = 0.4124 * r + 0.3576 * g + 0.1805 * b
    Y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    Z = 0.0193 * r + 0.1192 * g + 0.9505 * b

    def f(t):
        return t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = f(X / 0.95047), f(Y / 1.0), f(Z / 1.08883)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def lab_to_srgb_clamped(L, a, b):
    """缩小色度直到落入 sRGB 色域（保 L* 不保 C）。"""
    for scale in (1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.15, 0.1, 0.05, 0.0):
        fy = (L + 16) / 116
        fx, fz = fy + a * scale / 500, fy - b * scale / 200

        def fi(t):
            t3 = t ** 3
            return (t - 16 / 116) / 7.787 if t3 <= 0.008856 else t3
        X, Y, Z = fi(fx) * 0.95047, fi(fy), fi(fz) * 1.08883
        rl = 3.2406 * X - 1.5372 * Y - 0.4986 * Z
        gl = -0.9689 * X + 1.8758 * Y + 0.0415 * Z
        bl = 0.0557 * X - 0.2040 * Y + 1.0570 * Z
        if all(-0.001 <= v <= 1.001 for v in (rl, gl, bl)):
            return '#FF%02X%02X%02X' % (_linear_to_srgb(rl), _linear_to_srgb(gl), _linear_to_srgb(bl))
    return '#FF000000'


def rotate_hue(hexv, bucket_hue):
    # 保留原始 alpha：半透明洗色层（光晕/按压/聚焦）靠它叠出层次
    alpha = hexv[1:3]
    L, a, b = srgb_to_lab(hexv)
    chroma = math.hypot(a, b)
    if chroma < 2.0:   # 中性色不动
        return hexv
    chroma *= CHROMA_SCALE
    h = math.degrees(math.atan2(b, a)) % 360
    h2 = (bucket_hue + (h - BRAND_H)) % 360
    r = math.radians(h2)
    out = lab_to_srgb_clamped(L, chroma * math.cos(r), chroma * math.sin(r))
    return '#' + alpha + out[3:]


def attr_name(color):
    return 'pcDecor' + ''.join(part.title() for part in color.removeprefix('pc_item_').split('_'))


def card_palette(qualifier):
    return {node.attrib['name']: node.text for node in ET.parse(
        os.path.join(RES, qualifier, 'pc_item_colors.xml')).getroot()
        if node.tag == 'color' and not any(word in node.attrib['name']
            for word in ('text_', 'outline_', 'shimmer_'))}


def generated_resources():
    outputs = {}
    day = card_palette('values')
    attrs = [f'    <attr name="{attr_name(name)}" format="color" />' for name in day]
    attrs += [f'    <attr name="appAccent{alpha}" format="color" />' for alpha in ACCENT_ALPHAS]
    brand = ['    <style name="AppAccentBrand" parent="">',
        '        <item name="appAccent">@color/ui_shell_accent</item>',
        '        <item name="appAccentSoft">@color/ui_shell_accent_soft</item>',
        '        <item name="appAccentFocus">@color/ui_shell_accent_focus</item>']
    brand += [f'        <item name="{attr_name(name)}">@color/{name}</item>' for name in day]
    brand += [f'        <item name="appAccent{alpha}">@color/you_accent_{alpha}</item>' for alpha in ACCENT_ALPHAS]
    brand += ['    </style>']
    for qualifier in ('values', 'values-night'):
        lines = []
        if qualifier == 'values':
            lines += attrs + brand
        brand_color = next(node.text for node in ET.parse(
            Path(RES) / qualifier / 'advance_setting_colors.xml').getroot()
            if node.attrib.get('name') == 'ui_shell_accent')[-6:]
        lines += [f'    <color name="you_accent_{alpha}">#{round(255 * alpha / 100):02X}{brand_color}</color>'
            for alpha in ACCENT_ALPHAS]
        for bucket in range(12):
            if qualifier == 'values':
                base = rotate_hue('#FFFF6B9D', bucket * 30 + 15)
                lines += [f'    <color name="you_bg_accent_{bucket}">{base}</color>',
                    f'    <color name="you_bg_accent_{bucket}_soft">#1A{base[3:]}</color>',
                    f'    <color name="you_bg_accent_{bucket}_focus">#33{base[3:]}</color>']
                lines += [f'    <color name="you_bg_accent_{bucket}_a{alpha}">#{round(255 * alpha / 100):02X}{base[3:]}</color>'
                    for alpha in ACCENT_ALPHAS]
            palette = card_palette(qualifier)
            lines += [f'    <color name="{name}_b{bucket}">{rotate_hue(value, bucket * 30 + 15)}</color>'
                for name, value in palette.items()]
            if qualifier == 'values':
                lines += [f'    <style name="YouAccentOverlayBg{bucket}" parent="">',
                    f'        <item name="appAccent">@color/you_bg_accent_{bucket}</item>',
                    f'        <item name="appAccentSoft">@color/you_bg_accent_{bucket}_soft</item>',
                    f'        <item name="appAccentFocus">@color/you_bg_accent_{bucket}_focus</item>']
                lines += [f'        <item name="{attr_name(name)}">@color/{name}_b{bucket}</item>' for name in day]
                lines += [f'        <item name="appAccent{alpha}">@color/you_bg_accent_{bucket}_a{alpha}</item>'
                    for alpha in ACCENT_ALPHAS]
                lines += ['    </style>']
        outputs[Path(RES) / qualifier / 'you_bg_accent.xml'] = (
            '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            '    <!-- Generated by scripts/generate_accent_variants.py; do not edit. -->\n'
            + '\n'.join(lines) + '\n</resources>\n')
    return outputs


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail if generated resources are stale')
    args = parser.parse_args()
    for path, content in generated_resources().items():
        if args.check:
            if not path.exists() or path.read_text(encoding='utf-8') != content:
                raise SystemExit(f'Stale palette: {path}; run {Path(__file__).name}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8', newline='\n')
    print('Accent palettes verified' if args.check else 'Accent palettes generated')
