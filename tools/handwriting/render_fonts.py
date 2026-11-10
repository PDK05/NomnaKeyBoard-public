"""Dựng sẵn ảnh 64×64 của mọi chữ trong bộ chữ với mọi phông có chữ đó → cache/fonts.npz.

Ảnh chuẩn hoá theo khung bao mực như nét viết tay (synth.normalize), để lúc huấn luyện chỉ còn biến dạng.
"""

from multiprocessing import Pool

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from common import CACHE, font_cmaps, load_charset
from synth import MARGIN, SIZE

BIG = 160


def glyph(font, c):
    img = Image.new("L", (BIG, BIG), 0)
    ImageDraw.Draw(img).text((BIG // 2, BIG // 2), c, font=font, fill=255, anchor="mm")
    box = img.getbbox()
    if box is None:
        return None
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    if w * h < 16:
        return None
    s = (SIZE - 2 * MARGIN) / max(w, h)
    nw, nh = max(1, round(w * s)), max(1, round(h * s))
    small = img.crop(box).resize((nw, nh), Image.LANCZOS)
    out = Image.new("L", (SIZE, SIZE), 0)
    out.paste(small, ((SIZE - nw) // 2, (SIZE - nh) // 2))
    return np.asarray(out, dtype=np.uint8)


def work(args):
    (path, index), chars, have = args
    font = ImageFont.truetype(path, BIG * 3 // 4, index=index)
    imgs, labels = [], []
    for label, c in enumerate(chars):
        if ord(c) in have:
            g = glyph(font, c)
            if g is not None:
                imgs.append(g)
                labels.append(label)
    print(path, len(imgs), flush=True)
    return imgs, labels


def main():
    chars = load_charset()
    cmaps = font_cmaps()
    with Pool(8) as pool:
        parts = pool.map(work, [(k, chars, v) for k, v in cmaps.items()])
    imgs = np.stack([i for p in parts for i in p[0]])
    labels = np.array([l for p in parts for l in p[1]], dtype=np.int32)
    np.savez(CACHE / "fonts.npz", imgs=imgs, labels=labels)
    print("total", len(labels), "classes covered", len(set(labels.tolist())), "of", len(chars))


if __name__ == "__main__":
    main()
