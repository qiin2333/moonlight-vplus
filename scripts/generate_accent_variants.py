#!/usr/bin/env python3
"""生成首页背景强调色的 12 桶平色调色板与 overlay。

在仓库根目录运行 python scripts/generate_accent_variants.py，仅覆盖
app/src/main/res/values/you_bg_accent.xml。卡片渐变由 PcCardDecor 运行时生成。

调色算法与 CardAccentColors 保持一致：D65 sRGB / CIELAB 转换，
保留 L* 和 alpha，色度乘 0.8；超出 sRGB 色域时降低色度以保留明度和色相。
"""
import io
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
BRAND_H = 345.0           # 品牌粉色相锚点
CHROMA_SCALE = 0.8        # 色度缩放：等 C 下暖橙/蓝紫感知更艳，缩 0.8 对齐品牌粉的柔和感


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


def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(content)


def gen_flat_palette():
    """values/you_bg_accent.xml：12 桶平色 + soft/focus 洗色 + overlay 样式（单套稳定色，无日夜双套）。

    色值与装饰层同源：品牌粉 #FF6B9D 在 CIELAB 里做色相旋转（锁 L*/C×0.8），
    与卡片装饰使用相同的色相与色域处理。"""
    colors, styles = [], []
    for i in range(12):
        base = rotate_hue('#FFFF6B9D', i * 30 + 15)
        colors.append(f'    <color name="you_bg_accent_{i}">{base}</color>')
        colors.append(f'    <color name="you_bg_accent_{i}_soft">#1A{base[3:]}</color>')
        colors.append(f'    <color name="you_bg_accent_{i}_focus">#33{base[3:]}</color>')
        styles.append(f'    <style name="YouAccentOverlayBg{i}">')
        styles.append(f'        <item name="appAccent">@color/you_bg_accent_{i}</item>')
        styles.append(f'        <item name="appAccentSoft">@color/you_bg_accent_{i}_soft</item>')
        styles.append(f'        <item name="appAccentFocus">@color/you_bg_accent_{i}_focus</item>')
        styles.append('    </style>')
    write(os.path.join(RES, 'values', 'you_bg_accent.xml'),
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          '    <!-- 首页背景取色的 12 个色相桶（每桶 30°，中心角 +15°）。由 BgAccent 按背景图主导色相选择对应 overlay。 -->\n'
          + '\n'.join(colors) + '\n' + '\n'.join(styles) + '\n</resources>\n')
    print('values/you_bg_accent.xml written (色值 36 + 样式 12 个)')



if __name__ == '__main__':
    gen_flat_palette()
