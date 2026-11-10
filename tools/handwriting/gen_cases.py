"""Sinh ca kiểm tra cho `cargo run --example check` (handwriting/examples/cases.txt).

Mỗi dòng: chữ mong đợi <TAB> mô tả <TAB> các nét "x,y x,y;x,y ..." (toạ độ px trong ô 300×300).
Nét lấy từ Make Me a Hanzi (không làm méo), rồi đảo thứ tự / bỏ chấm / gộp chấm như người viết.
"""

import random

import numpy as np

from common import REPO
from synth import Composer

CELL = 300


def fmt(strokes):
    return ";".join(" ".join(f"{x * CELL:.1f},{y * CELL:.1f}" for x, y in s) for s in strokes)


def main():
    comp = Composer()
    rng = random.Random(5)
    g = comp.graphics
    rows = []

    def add(c, desc, strokes):
        rows.append(f"{c}\t{desc}\t{fmt(strokes)}")

    lei = g["泪"]  # 3 nét đầu là 氵 (chấm trên, chấm giữa, hất), sau là 目
    water, eye = lei[:3], lei[3:]
    add("泪", "thứ tự chuẩn", lei)
    add("泪", "viết 目 trước, thêm 氵 sau", eye + water)
    add("泪", "氵 viết chấm giữa, hất dưới, chấm trên", eye + [water[1], water[2], water[0]])
    add("泪", "氵 chỉ hai chấm", eye + [water[1], water[2]])
    add("泪", "氵 gộp thành một nét zíc zắc", eye + [np.concatenate(water)])
    add("泪", "氵 thành một nét sổ thẳng", eye + [np.array([water[0][0], water[2][-1]])])
    add("目", "chỉ 目 (trước khi thêm 氵)", eye)
    for c in "河喃漢字越":
        s = list(g[c])
        rng.shuffle(s)
        add(c, "đảo lộn thứ tự nét", s)
    for c in "𡨸𠊛𤾓𡗶":
        add(c, "chữ Nôm ghép từ IDS", comp.compose(c, rng))
    for c in "腀㗂":  # chữ V-source không có kVietnamese, thêm ở mô hình v2
        add(c, "chữ V-source ghép từ IDS", comp.compose(c, rng))
    (REPO / "handwriting/examples/cases.txt").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(len(rows), "cases")


if __name__ == "__main__":
    main()
