# 앱 아이콘 생성: teacher(남색 체크리스트), student(파란 공책+연필)
import math, os, sys
from PIL import Image, ImageDraw

def teacher(S, d):
    d.rounded_rectangle([0, 0, S-1, S-1], radius=int(S*0.22), fill=(27, 42, 73, 255))
    w = int(S*0.06)
    for i, y in enumerate([0.30, 0.50, 0.70]):
        cx, cy = S*0.26, S*y
        col = [(76, 211, 157), (122, 165, 255), (255, 180, 61)][i]
        d.line([(cx-S*0.07, cy), (cx-S*0.02, cy+S*0.05), (cx+S*0.07, cy-S*0.06)], fill=col, width=w, joint="curve")
        d.rounded_rectangle([S*0.42, cy-w/2, S*0.80, cy+w/2], radius=w//2, fill=(234, 240, 251, 255))

def student(S, d):
    d.rounded_rectangle([0, 0, S-1, S-1], radius=int(S*0.22), fill=(47, 111, 222, 255))
    d.rounded_rectangle([S*0.24, S*0.18, S*0.76, S*0.82], radius=int(S*0.05), fill=(255, 255, 255, 255))
    for y in (0.36, 0.48, 0.60):
        d.rounded_rectangle([S*0.34, S*y-S*0.018, S*0.66, S*y+S*0.018], radius=int(S*0.018), fill=(211, 221, 233, 255))
    w = S*0.07
    x1, y1, x2, y2 = S*0.50, S*0.80, S*0.82, S*0.36
    d.line([(x1, y1), (x2, y2)], fill=(255, 180, 61, 255), width=int(w))
    ang = math.atan2(y2-y1, x2-x1); px, py = -math.sin(ang)*w/2, math.cos(ang)*w/2
    tip = (x1-math.cos(ang)*w*1.1, y1-math.sin(ang)*w*1.1)
    d.polygon([(x1+px, y1+py), (x1-px, y1-py), tip], fill=(27, 42, 73, 255))

kind, out = sys.argv[1], sys.argv[2]
for dens, n in [("xxxhdpi", 192), ("xxhdpi", 144), ("xhdpi", 96), ("hdpi", 72), ("mdpi", 48)]:
    S = n*4
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    (teacher if kind == "teacher" else student)(S, ImageDraw.Draw(im))
    os.makedirs(f"{out}/mipmap-{dens}", exist_ok=True)
    im.resize((n, n), Image.LANCZOS).save(f"{out}/mipmap-{dens}/ic_launcher.png")
