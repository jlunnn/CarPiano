#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 app icon（🎹 鋼琴，5 個密度 + 圓形版）。

用法（喺 repo 根目錄）：python3 tools/make_icon.py
"""
from PIL import Image, ImageDraw
import os

S = 512
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app/src/main/res")


def master(round_icon=False):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    # 背景：由上至下 深藍 → 青綠（漸變）
    bg = Image.new("RGB", (S, S))
    db = ImageDraw.Draw(bg)
    for y in range(S):
        t = y / (S - 1)
        db.line([(0, y), (S, y)], fill=(int(30 + (8 - 30) * t),
                                       int(34 + (120 - 34) * t),
                                       int(70 + (156 - 70) * t)))
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    if round_icon:
        md.ellipse([0, 0, S - 1, S - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)
    img.paste(bg, (0, 0), mask)

    d = ImageDraw.Draw(img)
    kx0, ky0, kx1, ky1 = int(S * 0.09), int(S * 0.30), int(S * 0.91), int(S * 0.74)
    n = 8
    kw = (kx1 - kx0) / n

    # 白鍵
    for i in range(n):
        x0 = kx0 + i * kw
        d.rounded_rectangle([x0 + 2, ky0, x0 + kw - 2, ky1],
                            radius=int(kw * 0.10), fill=(250, 250, 252))
        d.rectangle([x0 + 2, ky0, x0 + kw - 2, ky0 + 6], fill=(226, 229, 236))

    # 黑鍵（每 7 個白鍵之後排：0,1,3,4,5）
    for i in range(n - 1):
        if (i % 7) in (0, 1, 3, 4, 5):
            cx = kx0 + (i + 1) * kw
            bw = kw * 0.56
            bh = (ky1 - ky0) * 0.60
            d.rounded_rectangle([cx - bw / 2, ky0, cx + bw / 2, ky0 + bh],
                                radius=int(bw * 0.18), fill=(22, 24, 32))

    # 上面加一個跳音音符 ♪（用簡單圓 + 桿畫）
    nx, ny = int(S * 0.30), int(S * 0.18)
    r = int(S * 0.055)
    d.ellipse([nx - r, ny - r * 0.78, nx + r, ny + r * 0.78], fill=(255, 209, 102))
    d.rectangle([nx + r * 0.72, ny - int(S * 0.115), nx + r * 0.95, ny + r * 0.6],
                fill=(255, 209, 102))
    return img


def save(img, name, size):
    os.makedirs(os.path.dirname(name), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(name)
    return name


base = master(False)
rnd = master(True)
for dpi, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    save(base, f"{OUT}/mipmap-{dpi}/ic_launcher.png", size)
    save(rnd, f"{OUT}/mipmap-{dpi}/ic_launcher_round.png", size)
save(base, f"{OUT}/mipmap-xxxhdpi/ic_launcher_foreground.png", 432)
print("icons OK")
