# -*- coding: utf-8 -*-
"""从 icon_source.png 生成 Android 启动图标全套资源(自适应 + 传统)。"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'icon_source.png')
RES = os.path.join(HERE, 'android-app', 'app', 'src', 'main', 'res')

BG = (255, 255, 255, 255)  # 白色背景

src = Image.open(SRC).convert('RGBA')

# 自适应图标前景: 108dp 画布, 安全区 66dp(≈61%), 图形留边距
DENSITIES = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}

for dpi, size in DENSITIES.items():
    d = os.path.join(RES, 'mipmap-%s' % dpi)
    os.makedirs(d, exist_ok=True)

    # ---- 前景(透明画布, 图形缩放到安全区) ----
    fg = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    glyph_size = int(size * 0.60)
    glyph = src.resize((glyph_size, int(glyph_size * src.size[1] / src.size[0])),
                       Image.LANCZOS)
    fg.paste(glyph, ((size - glyph.size[0]) // 2, (size - glyph.size[1]) // 2), glyph)
    fg.save(os.path.join(d, 'ic_launcher_foreground.png'))

    # ---- 传统图标: 白色圆角方块 + 图形占 72% ----
    legacy = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(legacy)
    radius = int(size * 0.22)
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=BG)
    glyph2 = src.resize((int(size * 0.72), int(size * 0.72 * src.size[1] / src.size[0])),
                        Image.LANCZOS)
    legacy.paste(glyph2, ((size - glyph2.size[0]) // 2, (size - glyph2.size[1]) // 2), glyph2)
    legacy.save(os.path.join(d, 'ic_launcher.png'))

    # ---- 圆形图标(部分启动器) ----
    rnd = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d2 = ImageDraw.Draw(rnd)
    d2.ellipse([0, 0, size - 1, size - 1], fill=BG)
    rnd.paste(glyph2, ((size - glyph2.size[0]) // 2, (size - glyph2.size[1]) // 2), glyph2)
    rnd.save(os.path.join(d, 'ic_launcher_round.png'))

print('图标 PNG 生成完毕')

# ---- anydpi 自适应图标定义 ----
anydpi = os.path.join(RES, 'mipmap-anydpi-v26')
os.makedirs(anydpi, exist_ok=True)
ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_bg" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'''
for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
    with open(os.path.join(anydpi, name), 'w', encoding='utf-8') as f:
        f.write(ADAPTIVE)
print('自适应图标 XML 生成完毕')

# ---- 背景色 ----
with open(os.path.join(RES, 'values', 'colors.xml'), 'w', encoding='utf-8') as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            '    <color name="ic_launcher_bg">#FFFFFF</color>\n</resources>\n')
print('colors.xml 生成完毕')
