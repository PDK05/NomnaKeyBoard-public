"""Dựng `fonts/hannom-extra.ttf`: phông dự phòng cho các chữ Hán Nôm mà `fonts/MiSans L3.ttf` thiếu.

MiSans L3 không có nhiều chữ Nôm mới (CJK mở rộng G, H, I, J) và vài chữ ở B–F, nên các chữ đó hiện
thành ô trắng. Jigmo (CC0, phủ đủ mọi khối CJK) có hết: lấy đúng các chữ ngoài BMP mà MiSans thiếu,
gộp từ ba tệp Jigmo thành một phông nhỏ.

    python extra.py            # phông Jigmo ở $HANNOM_DATA/fonts (mặc định D:/hannom-data/fonts)
"""

import os
import sys
from pathlib import Path

from fontTools import subset
from fontTools.merge import Merger
from fontTools.ttLib import TTFont

REPO = Path(__file__).resolve().parents[2]
DATA = Path(os.environ.get("HANNOM_DATA", "D:/hannom-data"))
JIGMO = ["Jigmo.ttf", "Jigmo2.ttf", "Jigmo3.ttf"]
# Bàn phím chỉ gắn phông Hán Nôm cho chữ từ U+20000 (xem `applyHanNomFont`).
FIRST = 0x20000


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    have = set(TTFont(REPO / "fonts/MiSans L3.ttf", lazy=True).getBestCmap())
    tmp = REPO / "fonts/.extra"
    tmp.mkdir(exist_ok=True)
    parts = []
    taken = set()
    for name in JIGMO:
        cmap = TTFont(DATA / "fonts" / name, lazy=True).getBestCmap()
        want = sorted(c for c in cmap if c >= FIRST and c not in have and c not in taken)
        if not want:
            continue
        taken.update(want)
        out = tmp / name
        options = subset.Options()
        options.layout_features = []
        options.name_IDs = ["*"]
        options.notdef_outline = True
        font = TTFont(DATA / "fonts" / name)
        sub = subset.Subsetter(options)
        sub.populate(unicodes=want)
        sub.subset(font)
        font.save(out)
        parts.append(out)
        print(f"{name}: {len(want)} chữ")
    merged = Merger().merge([str(p) for p in parts]) if len(parts) > 1 else TTFont(parts[0])
    dest = REPO / "fonts/hannom-extra.ttf"
    merged.save(dest)
    for p in parts:
        p.unlink()
    tmp.rmdir()
    print(f"{dest}: {len(taken)} chữ, {dest.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
