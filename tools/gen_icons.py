#!/usr/bin/env python3
"""
fFTP icon generator. One description of each variant -> SVG, Android vector
drawables and legacy PNG launcher icons.

    python3 tools/gen_icons.py        # apply variant 1
    python3 tools/gen_icons.py 3      # apply variant 3

Always (re)writes icons/variant-N.svg and icons/preview.png for all variants.
Needs Pillow (pip install pillow) for the PNG files.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ICONS = os.path.join(ROOT, "icons")

W, C, B = "#FFFFFF", "#80DEEA", "#1E88E5"      # white, light cyan, blue
BG1, BG2 = "#1565C0", "#00B8D4"                # gradient: deep blue -> cyan

# Shapes on a 108x108 grid (Android adaptive icon canvas, safe zone ~ 21..87):
#   ("line", [(x, y), ...], width, color)       round-capped polyline
#   ("dot", cx, cy, r, color)
#   ("ring", cx, cy, r, width, color)
#   ("rect", x, y, w, h, radius, color)
VARIANTS = {
    1: ("Exchange", [
        ("line", [(32, 44), (74, 44)], 7, W), ("line", [(64, 34), (74, 44), (64, 54)], 7, W),
        ("line", [(76, 64), (34, 64)], 7, C), ("line", [(44, 54), (34, 64), (44, 74)], 7, C)]),
    2: ("Monogram f", [
        ("line", [(66, 35), (57, 35), (48, 44), (48, 77)], 8, W),
        ("line", [(36, 53), (64, 53)], 8, W),
        ("dot", 67, 74, 5, C)]),
    3: ("Folder", [
        ("rect", 30, 46, 48, 32, 6, W), ("rect", 30, 38, 24, 14, 5, W),
        ("line", [(54, 54), (54, 69)], 5, B), ("line", [(47, 63), (54, 70), (61, 63)], 5, B)]),
    4: ("Stack", [
        ("rect", 30, 34, 48, 10, 5, W), ("rect", 30, 49, 34, 10, 5, C),
        ("rect", 30, 64, 40, 10, 5, W), ("dot", 72, 54, 5, W)]),
    5: ("Orbit", [
        ("ring", 54, 54, 24, 6, W), ("dot", 54, 54, 9, C), ("dot", 71, 37, 6, C)]),
}


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def fmt(v):
    return ("%g" % v)


# ---------------- SVG ----------------

def svg(shapes, rounded=True):
    o = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="216" height="216">',
         '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
         '<stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient></defs>' % (BG1, BG2),
         '<rect width="108" height="108" rx="%d" fill="url(#g)"/>' % (24 if rounded else 0)]
    for s in shapes:
        k = s[0]
        if k == "line":
            d = "M" + " L".join("%s %s" % (fmt(x), fmt(y)) for x, y in s[1])
            o.append('<path d="%s" fill="none" stroke="%s" stroke-width="%s" stroke-linecap="round" '
                     'stroke-linejoin="round"/>' % (d, s[3], fmt(s[2])))
        elif k == "dot":
            o.append('<circle cx="%s" cy="%s" r="%s" fill="%s"/>' % (fmt(s[1]), fmt(s[2]), fmt(s[3]), s[4]))
        elif k == "ring":
            o.append('<circle cx="%s" cy="%s" r="%s" fill="none" stroke="%s" stroke-width="%s"/>'
                     % (fmt(s[1]), fmt(s[2]), fmt(s[3]), s[5], fmt(s[4])))
        elif k == "rect":
            o.append('<rect x="%s" y="%s" width="%s" height="%s" rx="%s" fill="%s"/>'
                     % (fmt(s[1]), fmt(s[2]), fmt(s[3]), fmt(s[4]), fmt(s[5]), s[6]))
    o.append("</svg>")
    return "\n".join(o) + "\n"


# ---------------- Android vector drawable ----------------

def circle_path(cx, cy, r):
    return "M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 Z" % (
        fmt(cx - r), fmt(cy), fmt(r), fmt(r), fmt(2 * r), fmt(r), fmt(r), fmt(-2 * r))


def rect_path(x, y, w, h, r):
    r = min(r, w / 2.0, h / 2.0)
    return ("M%s,%s H%s a%s,%s 0 0,1 %s,%s V%s a%s,%s 0 0,1 %s,%s H%s a%s,%s 0 0,1 %s,%s V%s "
            "a%s,%s 0 0,1 %s,%s Z") % (
        fmt(x + r), fmt(y), fmt(x + w - r), fmt(r), fmt(r), fmt(r), fmt(r), fmt(y + h - r),
        fmt(r), fmt(r), fmt(-r), fmt(r), fmt(x + r), fmt(r), fmt(r), fmt(-r), fmt(-r),
        fmt(y + r), fmt(r), fmt(r), fmt(r), fmt(-r))


def vector(shapes, dp, view, dx=0, dy=0, mono=False):
    o = ['<?xml version="1.0" encoding="utf-8"?>',
         '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
         '    android:width="%sdp"' % dp, '    android:height="%sdp"' % dp,
         '    android:viewportWidth="%s"' % view, '    android:viewportHeight="%s">' % view]

    def col(c):
        return "#FFFFFF" if mono else c

    for s in shapes:
        k = s[0]
        if mono and s[-1] == B:
            continue                                  # blue "cut-outs" vanish in a mono icon
        if k == "line":
            d = "M" + " L".join("%s,%s" % (fmt(x + dx), fmt(y + dy)) for x, y in s[1])
            o.append('    <path android:pathData="%s" android:strokeColor="%s" android:strokeWidth="%s" '
                     'android:strokeLineCap="round" android:strokeLineJoin="round"/>' % (d, col(s[3]), fmt(s[2])))
        elif k == "dot":
            o.append('    <path android:pathData="%s" android:fillColor="%s"/>'
                     % (circle_path(s[1] + dx, s[2] + dy, s[3]), col(s[4])))
        elif k == "ring":
            o.append('    <path android:pathData="%s" android:strokeColor="%s" android:strokeWidth="%s"/>'
                     % (circle_path(s[1] + dx, s[2] + dy, s[3]), col(s[5]), fmt(s[4])))
        elif k == "rect":
            o.append('    <path android:pathData="%s" android:fillColor="%s"/>'
                     % (rect_path(s[1] + dx, s[2] + dy, s[3], s[4], s[5]), col(s[6])))
    o.append("</vector>")
    return "\n".join(o) + "\n"


# ---------------- PNG ----------------

def render(shapes, size, legacy=True, ss=4):
    n = size * ss
    k = n / 108.0
    g = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    for s in shapes:
        t = s[0]
        if t == "line":
            col, w = rgb(s[3]), s[2] * k
            pts = [(x * k, y * k) for x, y in s[1]]
            d.line(pts, fill=col, width=int(round(w)), joint="curve")
            for x, y in pts:
                d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=col)
        elif t == "dot":
            cx, cy, r = s[1] * k, s[2] * k, s[3] * k
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=rgb(s[4]))
        elif t == "ring":
            cx, cy, r, w = s[1] * k, s[2] * k, s[3] * k, s[4] * k
            ro = r + w / 2
            d.ellipse([cx - ro, cy - ro, cx + ro, cy + ro], outline=rgb(s[5]), width=int(round(w)))
        elif t == "rect":
            d.rounded_rectangle([s[1] * k, s[2] * k, (s[1] + s[3]) * k, (s[2] + s[4]) * k],
                                radius=s[5] * k, fill=rgb(s[6]))
    g = g.resize((size, size), Image.LANCZOS)

    a, b = rgb(BG1), rgb(BG2)
    bg = Image.new("RGBA", (size, size))
    px = []
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2.0 * max(size - 1, 1))
            px.append(tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,))
    bg.putdata(px)

    m = Image.new("L", (n, n), 0)
    inset, rad = (6, 24) if legacy else (0, 0)
    ImageDraw.Draw(m).rounded_rectangle([inset * k, inset * k, (108 - inset) * k - 1, (108 - inset) * k - 1],
                                        radius=rad * k, fill=255)
    bg.putalpha(m.resize((size, size), Image.LANCZOS))
    return Image.alpha_composite(bg, g)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    pick = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    if pick not in VARIANTS:
        sys.exit("variant must be 1..%d" % len(VARIANTS))

    os.makedirs(ICONS, exist_ok=True)
    sheet = Image.new("RGBA", (len(VARIANTS) * 190 + 10, 230), (255, 255, 255, 255))
    d = ImageDraw.Draw(sheet)
    for i, (name, shapes) in sorted(VARIANTS.items()):
        write(os.path.join(ICONS, "variant-%d.svg" % i), svg(shapes))
        sheet.alpha_composite(render(shapes, 160), (10 + (i - 1) * 190, 10))
        d.text((10 + (i - 1) * 190 + 55, 185), "%d  %s" % (i, name), fill=(30, 40, 60, 255),
               font=ImageFont.load_default())
    sheet.convert("RGB").save(os.path.join(ICONS, "preview.png"))

    shapes = VARIANTS[pick][1]
    for folder, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        p = os.path.join(RES, "mipmap-" + folder, "ic_launcher.png")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        render(shapes, px).save(p, optimize=True)

    write(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"),
          '<?xml version="1.0" encoding="utf-8"?>\n'
          '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
          '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
          '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
          '</adaptive-icon>\n')
    write(os.path.join(RES, "drawable-v26", "ic_launcher_background.xml"),
          '<?xml version="1.0" encoding="utf-8"?>\n'
          '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
          '    <gradient android:angle="315" android:startColor="%s" android:endColor="%s"/>\n'
          '</shape>\n' % (BG1, BG2))
    write(os.path.join(RES, "drawable-v26", "ic_launcher_foreground.xml"), vector(shapes, 108, 108))
    # status-bar icon: 24dp, glyph cropped from the 108 grid (24..84), white only
    write(os.path.join(RES, "drawable", "ic_stat.xml"), vector(shapes, 24, 60, dx=-24, dy=-24, mono=True))
    print("applied variant %d (%s)" % (pick, VARIANTS[pick][0]))


if __name__ == "__main__":
    main()
