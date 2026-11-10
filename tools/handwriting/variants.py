"""Ghi `assets/variants.txt`: các nhóm dị thể (mỗi dòng một nhóm, các chữ viết liền) cho gợi ý viết tay.

Viết ra một chữ thì gợi ý kèm các dị thể của nó (国 → 國 囯 …), vì mô hình hay nhầm giữa các dị thể
chỉ khác một nét, nhất là với chữ ít mẫu huấn luyện. Nhóm là thành phần liên thông theo các quan hệ
kSemanticVariant, kZVariant, kSimplifiedVariant, kTraditionalVariant trong Unihan, chỉ giữ chữ có
trong `charset.txt` (mô hình nhận được).

    python tools/handwriting/variants.py
"""

import re
import sys

from common import DATA, REPO, load_charset

KINDS = ("kSemanticVariant", "kZVariant", "kSimplifiedVariant", "kTraditionalVariant")
# Nhóm lớn hơn thế thường là chuỗi nối nhầm qua nhiều quan hệ xa, bỏ.
MAX_GROUP = 8


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    chars = set(load_charset())
    parent = {}

    def find(c):
        while parent.get(c, c) != c:
            parent[c] = parent.get(parent[c], parent[c])
            c = parent[c]
        return c

    for line in open(DATA / "raw/unihan/Unihan_Variants.txt", encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        code, kind, value = line.rstrip("\n").split("\t")
        if kind not in KINDS:
            continue
        a = chr(int(code[2:], 16))
        for m in re.findall(r"U\+([0-9A-F]+)", value):
            b = chr(int(m, 16))
            if a in chars and b in chars and a != b:
                parent[find(a)] = find(b)

    groups = {}
    for c in parent:
        groups.setdefault(find(c), []).append(c)
    groups = [sorted(g, key=ord) for g in groups.values() if 2 <= len(g) <= MAX_GROUP]
    out = REPO / "mobile/android/app/src/main/assets/variants.txt"
    out.write_text("".join("".join(g) + "\n" for g in sorted(groups)), encoding="utf-8")
    print(len(groups), "nhóm,", sum(map(len, groups)), "chữ")


if __name__ == "__main__":
    main()
