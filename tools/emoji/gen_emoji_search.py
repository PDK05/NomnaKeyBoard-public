"""Tạo mobile/android/app/src/main/assets/emoji_search.txt (từ khoá tìm emoji) từ chú thích CLDR.

    python -I tools/emoji/gen_emoji_search.py <thư mục cldr> mobile/android/app/src/main/assets

<thư mục cldr> chứa các tệp tải từ https://github.com/unicode-org/cldr/tree/release-48/common:
annotations/vi.xml → annotations_vi.xml, annotationsDerived/vi.xml → annotationsDerived_vi.xml, và tương tự
cho `en`. CLDR 48 đi cùng Unicode 17, đủ tên cho mọi emoji trong bảng của Gboard (emoji.txt).

Mỗi dòng một ô của emoji.txt: emoji hiện trong ô, tab, tên tiếng Việt, tab, từ khoá cách nhau ` | ` (tiếng
Việt rồi tiếng Anh, gồm cả từ khoá của các bản giới tính trong ô; bỏ bản màu da vì tên chỉ thêm màu da).
"""

import os
import sys
import xml.etree.ElementTree as ET

SKINS = set(range(0x1F3FB, 0x1F400))


def norm(s):
    return "".join(c for c in s if c != "️")


def load(folder, lang):
    """Emoji (bỏ FE0F) → (tên, [từ khoá])."""
    out = {}
    for name in (f"annotations_{lang}.xml", f"annotationsDerived_{lang}.xml"):
        for a in ET.parse(os.path.join(folder, name)).getroot().iter("annotation"):
            cp = norm(a.get("cp"))
            name_, words = out.get(cp, (None, []))
            if a.get("type") == "tts":
                name_ = a.text.strip()
            else:
                words = words + [w.strip() for w in a.text.split("|") if w.strip()]
            out[cp] = (name_, words)
    return out


def main(folder, assets):
    vi, en = load(folder, "vi"), load(folder, "en")
    lines = []
    for line in open(os.path.join(assets, "emoji.txt"), encoding="utf-8"):
        if line.startswith("#") or line.startswith("=") or not line.strip():
            continue
        for cell in line.split():
            variants = [r.split(",")[0] for r in cell.split("|")]
            base = variants[0]
            name = None
            words = []
            for lang in (vi, en):
                for v in variants:
                    n, ws = lang.get(norm(v), (None, []))
                    if n and name is None and lang is vi:
                        name = n
                    words += ([n] if n else []) + ws
            if name is None:
                name = en.get(norm(base), (base, []))[0]
            seen = set()
            uniq = [w for w in words if not (w.lower() in seen or seen.add(w.lower()))]
            lines.append(f"{base}\t{name}\t{' | '.join(uniq)}")
    with open(os.path.join(assets, "emoji_search.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# Tạo bằng tools/emoji/gen_emoji_search.py từ chú thích CLDR 48: emoji, tab, tên, tab, từ khoá cách nhau |.\n")
        f.write("\n".join(lines) + "\n")
    print(len(lines), "ô")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
