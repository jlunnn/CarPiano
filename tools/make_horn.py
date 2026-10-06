#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「horn beep beep」音效（純 Python stdlib，唔靠 numpy）。

用法（喺 repo 根目錄）：python3 tools/make_horn.py
"""
import math
import os
import struct
import wave

RATE = 44100
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app/src/main/assets/horn.wav")

# 汽車喇叭 = 兩個略為唔同頻率嘅音同時響（會產生打拍子嘅「哇」聲）：
#   低音 400 Hz + 高音 505 Hz，加少少諧波同起落包絡，避免爆音（click）。
# 兩短響：0.28 s 響 + 0.13 s 停 + 0.28 s 響。


def horn_seg(dur, f_lo=400.0, f_hi=505.0):
    n = int(RATE * dur)
    out = []
    for i in range(n):
        t = i / RATE
        # 起落包絡（8 ms fade in/out）→ 唔會有 click
        env = 1.0
        fade = int(RATE * 0.008)
        if i < fade:
            env = i / fade
        elif i > n - fade:
            env = (n - i) / fade
        # 每個喇叭音：基頻 + 2/3 次諧波（喇叭唔係純正弦）
        v = (math.sin(2 * math.pi * f_lo * t)
             + 0.32 * math.sin(2 * math.pi * f_lo * 2 * t)
             + 0.14 * math.sin(2 * math.pi * f_lo * 3 * t)
             + math.sin(2 * math.pi * f_hi * t)
             + 0.30 * math.sin(2 * math.pi * f_hi * 2 * t))
        v = v / 3.0 * env
        out.append(v)
    return out


def silence(dur):
    return [0.0] * int(RATE * dur)


samples = horn_seg(0.28) + silence(0.13) + horn_seg(0.28)
# 正規化到 −3 dBFS
peak = max(abs(s) for s in samples) or 1.0
scale = 0.707 / peak

with wave.open(OUT, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(b"".join(
        struct.pack("<h", int(max(-32767, min(32767, s * scale * 32767)))) for s in samples))

print("OK", OUT, "%.2f s" % (len(samples) / RATE))
