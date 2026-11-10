"""Tạo emoji-names.md: tên từng ô emoji (tiếng Việt, tiếng Anh theo CLDR 48), cột Hán Nôm để người dùng điền.

    python -I tools/emoji/gen_emoji_names.py <thư mục cldr> mobile/android/app/src/main/assets emoji-names.md \
        dict/nomna-dict.txt

<thư mục cldr> như của gen_emoji_search.py. Mỗi ô của bảng emoji một dòng, theo nhóm và thứ tự trong `emoji.txt`;
ô có bản giới tính / hướng thì ghi thêm các bản đó (màu da không ghi, tên chỉ thêm màu da). Cột Hán Nôm: ô trống
thì tự sinh từ tên tiếng Việt theo từ điển Nôm Na ([to_nom]); ô đã có chữ (người dùng sửa) thì giữ khi chạy lại.
"""

import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_emoji_search import load, norm  # noqa: E402


def old_nom(path):
    """Emoji → tên Hán Nôm đã điền trong tệp cũ."""
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        cols = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cols) >= 6 and cols[0].isdigit() and cols[5]:
            out[cols[1]] = cols[5]
    return out


def load_dict(path):
    """Âm đọc (một hay nhiều âm tiết, chữ thường) → chữ phổ biến nhất trong từ điển Nôm Na."""
    best = {}
    for line in open(path, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        parts = line.rstrip("\n").split(";")
        if len(parts) < 3:
            continue
        chars, reading, freq = parts[0], parts[1].lower(), int(parts[2] or 0)
        if reading not in best or freq > best[reading][1]:
            best[reading] = (chars, freq)
    return {k: v[0] for k, v in best.items()}


TONES = "̣̀́̃̉"  # huyền sắc ngã hỏi nặng
VOWELS = "aăâeêioôơuưy"


def spellings(syllable):
    """
    Các cách viết của một âm tiết: dấu thanh đặt kiểu cũ / mới (khóa / khoá, thủy / thuỷ) và i / y cuối (kỹ / kĩ,
    mỳ / mì), để khớp từ điển viết theo kiểu khác.
    """
    d = unicodedata.normalize("NFD", syllable)
    tone = next((c for c in d if c in TONES), "")
    base = unicodedata.normalize("NFC", "".join(c for c in d if c not in TONES))
    bases = {base}
    if base.endswith("y") and len(base) >= 2 and base[-2] not in VOWELS:
        bases.add(base[:-1] + "i")
    if base.endswith("i") and len(base) >= 2 and base[-2] not in VOWELS:
        bases.add(base[:-1] + "y")
    out = []
    for b in bases:
        if not tone:
            out.append(b)
            continue
        for i, c in enumerate(b):
            if c in VOWELS:
                out.append(unicodedata.normalize("NFC", b[:i + 1] + tone + b[i + 1:]))
    return [syllable] + [s for s in out if s != syllable]


def lookup(tokens, words):
    """Chữ của cụm [tokens] (chữ thường) trong từ điển, thử các cách viết của từng âm tiết; không có thì None."""
    options = [[""]]
    for t in tokens:
        options = [o + [s] for o in options for s in spellings(t)][:64]
    for o in options:
        key = " ".join(o[1:])
        if key in words:
            return words[key]
    return None


def to_nom(name, words, longest):
    """
    Tên tiếng Việt → Hán Nôm tạm: tách âm tiết, khớp cụm dài nhất có trong từ điển, lấy chữ phổ biến nhất. Âm không
    có trong từ điển (chữ Latin, số, ký hiệu) giữ nguyên, cách bằng dấu cách.
    """
    tokens = re.findall(r"[^\W\d_]+|\d+|[^\s\w]", name)
    out = []
    i = 0
    while i < len(tokens):
        for n in range(min(longest, len(tokens) - i), 0, -1):
            found = lookup([t.lower() for t in tokens[i:i + n]], words)
            if found:
                out.append(("nom", found))
                i += n
                break
        else:
            out.append(("raw", tokens[i]))
            i += 1
    # Chữ Hán viết liền; chữ giữ nguyên (Latin, số) cách với bên cạnh; dấu câu dính vào chữ trước.
    text = ""
    prev = None
    for kind, t in out:
        punct = kind == "raw" and not re.match(r"\w", t)
        if prev is not None and not punct and not (kind == "nom" and prev == "nom"):
            text += " "
        text += t
        prev = "punct" if punct else kind
    return text


def main(folder, assets, dst, dict_path):
    vi, en = load(folder, "vi"), load(folder, "en")
    words = load_dict(dict_path)
    longest = max(len(k.split()) for k in words)
    kept = old_nom(dst)
    # Dữ liệu tìm của từng ô, đúng như bàn phím dùng (assets/emoji_search.txt): tên và từ khoá tiếng Việt, tiếng Anh.
    search = {}
    for line in open(os.path.join(assets, "emoji_search.txt"), encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 3 and not line.startswith("#"):
            search[parts[0]] = parts[2].replace(" | ", ", ")
    lines = [
        "# Tên emoji",
        "",
        "> **Chưa thống nhất.** Cột Hán Nôm là bản tạm sinh tự động, chưa duyệt; bàn phím chưa dùng bảng này (tìm emoji",
        "> bằng chữ Hán Nôm đi theo âm đọc của chữ trong từ điển, không theo cột này).",
        "",
        "Tên từng ô của bảng emoji, theo nhóm và đúng thứ tự trên bàn phím. Tên tiếng Việt, tiếng Anh lấy từ chú thích",
        "CLDR 48 của Unicode. Cột **Hán Nôm** tạm sinh tự động từ tên tiếng Việt theo từ điển `dict/nomna-dict.txt`",
        "(khớp cụm dài nhất, chọn chữ phổ biến nhất; âm không có trong từ điển giữ nguyên), cần duyệt lại; chưa dùng",
        "trong bàn phím. Cột *Bản khác* là các bản giới tính / hướng gộp trong ô (giữ ô để chọn); màu da không ghi riêng.",
        "Cột **Dữ liệu tìm** là đúng những chữ ô tìm emoji so khớp (`assets/emoji_search.txt`): tên và từ khoá CLDR",
        "tiếng Việt rồi tiếng Anh, gồm cả của các bản khác trong ô. Chữ Hán Nôm gõ vào ô tìm khớp khi một âm đọc của nó",
        "trùng một âm tiết bất kỳ trong cột này (ví dụ 𩵜 \"cá\" khớp 💻 qua từ khoá \"cá nhân\").",
        "",
        "Tạo bằng `tools/emoji/gen_emoji_names.py`; chạy lại thì chữ Hán Nôm đã có trong tệp được giữ, ô trống thì sinh lại.",
        "",
    ]
    n = 0
    group = None
    for line in open(os.path.join(assets, "emoji.txt"), encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        if line.startswith("="):
            group = line[1:].strip()
            continue
        lines += ["", f"## {group}", "",
                  "| STT | Emoji | Tiếng Việt | Tiếng Anh | Bản khác | Hán Nôm | Dữ liệu tìm |",
                  "|----:|:-----:|------------|-----------|----------|---------|-------------|"]
        for cell in line.split():
            variants = [r.split(",")[0] for r in cell.split("|")]
            base = variants[0]
            n += 1
            name_vi = vi.get(norm(base), (None, []))[0] or ""
            name_en = en.get(norm(base), (None, []))[0] or ""
            others = []
            for v in variants[1:]:
                vn = vi.get(norm(v), (None, []))[0] or en.get(norm(v), (None, []))[0] or ""
                others.append(f"{v} {vn}".strip())
            nom = kept.get(base) or (to_nom(name_vi, words, longest) if name_vi else "")
            cols = [str(n), base, name_vi, name_en, "; ".join(others), nom, search.get(base, "")]
            lines.append("| " + " | ".join(c.replace("|", "\\|") for c in cols) + " |")
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(n, "ô")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
