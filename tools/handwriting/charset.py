"""Chọn bộ chữ cho mô hình viết tay và ghi `charset.txt` (mỗi dòng một chữ, thứ tự = lớp).

Bộ chữ = chữ có âm Việt trong Unihan (kVietnamese) ∪ chữ Việt Nam đệ trình lên Unicode
(kIRG_VSource, gồm nhiều chữ Nôm chưa có âm như 腀) ∪ chữ Trung chuẩn (Thông dụng Quy phạm
kTGH, phồn thể Big5 kBigFive) ∪ chữ có nét trong Make Me a Hanzi
∪ chữ trong từ điển mẫu, chỉ giữ chữ mà ít nhất một phông hiển thị được hoặc dựng được từ nét.

Chữ đã có trong `charset.txt` cũ giữ nguyên vị trí, chữ mới nối vào cuối, để mô hình cũ
huấn luyện tiếp được (`train.py --init`).
"""

import json
import sys

from common import DATA, REPO, cjk, font_cmaps, load_charset, load_mmah
from synth import Composer


def main():
    chars = set()
    for line in open(DATA / "raw/unihan/Unihan_Readings.txt", encoding="utf-8"):
        if "\tkVietnamese\t" in line:
            chars.add(chr(int(line.split("\t")[0][2:], 16)))
    for line in open(DATA / "raw/unihan/Unihan_IRGSources.txt", encoding="utf-8"):
        if "\tkIRG_VSource\t" in line:
            chars.add(chr(int(line.split("\t")[0][2:], 16)))
    for line in open(DATA / "raw/unihan/Unihan_OtherMappings.txt", encoding="utf-8"):
        if "\tkTGH\t" in line or "\tkBigFive\t" in line:
            chars.add(chr(int(line.split("\t")[0][2:], 16)))
    graphics, _ = load_mmah()
    chars |= set(graphics)
    for line in open(REPO / "dict/nomna-dict.txt", encoding="utf-8"):
        if line.strip() and not line.startswith("#"):
            chars.add(line.split(";")[0].strip())
    chars = {c for c in chars if len(c) == 1 and cjk(c)}

    cmaps = font_cmaps()
    composer = Composer()
    in_font = {c for c in chars if any(ord(c) in cm for cm in cmaps.values())}
    strokes = {c for c in chars if composer.can_compose(c)}
    usable = in_font | strokes
    path = DATA / "charset.txt"
    old = load_charset() if path.exists() else []
    keep = old + sorted(usable - set(old), key=ord)

    path.write_text("\n".join(keep) + "\n", encoding="utf-8")
    stats = {
        "candidates": len(chars),
        "kept": len(keep),
        "new": len(keep) - len(old),
        "font_only": len(in_font - strokes),
        "strokes": len(strokes),
        "mmah_direct": len(chars & set(graphics)),
        "dropped": len(chars) - len(keep),
    }
    json.dump(stats, sys.stdout, indent=1)


if __name__ == "__main__":
    main()
