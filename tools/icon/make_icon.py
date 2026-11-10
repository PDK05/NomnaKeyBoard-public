"""Sinh adaptive icon Android từ `logo.png` (chữ vàng trên nền đỏ trơn, vuông).

Lớp nền là màu đỏ của logo (`values/ic_launcher_colors.xml`); lớp trước là chữ tách khỏi nền,
thu nhỏ sao cho mọi điểm của chữ nằm trong vòng tròn an toàn 66dp của khung 108dp.

Icon màn hình chờ (Android 12+, `splash_icon`): ô vuông đỏ bo góc kèm chữ, trên khung 288dp mà
hệ thống cắt theo vòng tròn 192dp, nên ô vuông phải đủ nhỏ để cả bốn góc bo nằm trong vòng tròn.

    python make_icon.py
"""

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
RES = HERE.parents[1] / "mobile/android/app/src/main/res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
SAFE_RADIUS = 32  # dp, chừa 1dp so với vòng tròn an toàn 66dp
SPLASH = 288  # dp, khung icon màn hình chờ không có nền riêng
SPLASH_SIDE = 152  # dp, cạnh ô vuông: (152/2 - r)·√2 + r ≤ 96 với r = 0.22·cạnh
SPLASH_CORNER = 0.22
SPLASH_REACH = 0.36  # bán kính phủ của chữ so với cạnh ô vuông


def main():
    img = np.asarray(Image.open(HERE / "logo.png").convert("RGB")).astype(np.float32)
    bg = img[2, 2]
    # Nền đỏ có kênh xanh lá ≈ 0, chữ vàng ≥ 150: dùng kênh xanh lá làm độ phủ (giữ viền mượt).
    alpha = np.clip((img[..., 1] - bg[1] - 10) / 120, 0, 1)
    ys, xs = np.nonzero(alpha > 0.05)
    cy, cx = (ys.min() + ys.max()) / 2, (xs.min() + xs.max()) / 2
    reach = np.sqrt((ys - cy) ** 2 + (xs - cx) ** 2).max()

    rgba = np.dstack([img, alpha * 255]).astype(np.uint8)
    glyph = Image.fromarray(rgba, "RGBA")
    for name, scale in DENSITIES.items():
        size = round(108 * scale)
        k = SAFE_RADIUS * scale / reach
        small = glyph.resize((round(glyph.width * k), round(glyph.height * k)), Image.LANCZOS)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.alpha_composite(small, (round(size / 2 - cx * k), round(size / 2 - cy * k)))
        path = RES / f"drawable-{name}/ic_launcher_foreground.png"
        path.parent.mkdir(exist_ok=True)
        out.save(path, optimize=True)

        size, side = round(SPLASH * scale), round(SPLASH_SIDE * scale)
        k = SPLASH_REACH * side / reach
        small = glyph.resize((round(glyph.width * k), round(glyph.height * k)), Image.LANCZOS)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        x0 = (size - side) // 2
        ImageDraw.Draw(out).rounded_rectangle(
            (x0, x0, x0 + side - 1, x0 + side - 1), radius=round(side * SPLASH_CORNER), fill=tuple(bg.astype(int)) + (255,)
        )
        out.alpha_composite(small, (round(size / 2 - cx * k), round(size / 2 - cy * k)))
        out.save(RES / f"drawable-{name}/splash_icon.png", optimize=True)

    color = "#{:02X}{:02X}{:02X}".format(*bg.astype(int))
    (RES / "values/ic_launcher_colors.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <color name="ic_launcher_background">{color}</color>\n</resources>\n',
        encoding="utf-8",
    )
    print("nền", color, "chữ chiếm", f"{2 * SAFE_RADIUS}dp/108dp")


if __name__ == "__main__":
    main()
