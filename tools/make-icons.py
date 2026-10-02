#!/usr/bin/env python
"""生成四种电量状态的启动图标（传统 PNG 密度桶）+ 核对自适应图标资源。

为什么 PNG 和自适应图标都要有：
  - API 26+ 用 mipmap-anydpi-v26/ic_launcher_*.xml（自适应图标，矢量，已在仓库里）
  - API 24~25 只认传统 PNG，没有对应密度的 PNG 就会在旧机上显示空白/默认图标
所以这里为 ok/warn/low/unknown 各生成 mdpi~xxxhdpi 五个密度的 PNG。

用法：python tools/make-icons.py
"""
import os
import sys
from PIL import Image, ImageDraw

# Windows 控制台默认可能是 GBK，直接 print 非 ASCII 会抛 UnicodeEncodeError
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# 与 colors.xml / colors 资源保持一致
STATES = {
    "ok": (0x1F, 0x7A, 0x45),
    "warn": (0xB7, 0x79, 0x1F),
    "low": (0xC5, 0x30, 0x30),
    "unknown": (0x6B, 0x7A, 0x72),
}

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

SS = 4  # 超采样倍数

# 闪电多边形（与 ic_bolt_foreground.xml 里的 Material bolt 形状近似，归一化坐标）
BOLT = [
    (0.585, 0.085),
    (0.275, 0.545),
    (0.470, 0.545),
    (0.405, 0.935),
    (0.735, 0.430),
    (0.525, 0.430),
]


def rounded_mask(size, radius_ratio=0.22):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255)
    return mask


def draw_icon(size, color, bolt_ratio=0.56):
    big = size * SS
    img = Image.new("RGBA", (big, big), color + (255,))
    layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    cx = cy = big / 2.0
    pts = [(cx + (x - 0.5) * big * bolt_ratio, cy + (y - 0.5) * big * bolt_ratio) for x, y in BOLT]
    d.polygon(pts, fill=(255, 255, 255, 255))
    img.alpha_composite(layer)
    img.putalpha(rounded_mask(big))
    return img.resize((size, size), Image.LANCZOS)


def main():
    made = 0
    for density, size in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        for state, color in STATES.items():
            path = os.path.join(folder, "ic_launcher_%s.png" % state)
            draw_icon(size, color).save(path)
            made += 1
        print("  mipmap-%-8s %2dpx  ×4 状态" % (density, size))
    print("共生成 %d 个 PNG" % made)

    # 核对自适应图标（矢量，手写在仓库里）是否四个状态齐全
    missing = []
    for state in STATES:
        adaptive = os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher_%s.xml" % state)
        if not os.path.exists(adaptive):
            missing.append(adaptive)
    if missing:
        print("  缺少自适应图标：")
        for m in missing:
            print("    " + m)
    else:
        print("  [OK] 四个状态的自适应图标齐全（mipmap-anydpi-v26）")


if __name__ == "__main__":
    main()
