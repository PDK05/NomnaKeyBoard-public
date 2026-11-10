"""Không dùng nữa: emoji.txt giờ tạo bằng gen_emoji_gboard.py (đúng nhóm và thứ tự của Gboard); bản này xếp theo
Unicode nên lệch Gboard. Giữ lại để tham khảo.

Tạo mobile/android/app/src/main/assets/emoji.txt từ emoji-test.txt của Unicode, xếp nhóm như Gboard.

    python -I tools/emoji/gen_emoji.py emoji-test.txt mobile/android/app/src/main/assets/emoji.txt

emoji-test.txt: https://unicode.org/Public/emoji/15.1/emoji-test.txt

Như bảng emoji của Gboard:
- Bàn tay, bộ phận cơ thể, 🗣 👤 👥 🫂 👣 nằm trong nhóm mặt cười; nhóm người chỉ còn người.
- Không có emoji gia đình (👪, 👨‍👩‍👦…).
- Bản nam / nữ gộp vào ô của bản trung tính (🙇 gồm 🙇‍♂️ 🙇‍♀️, 🧑‍⚕️ gồm 👨‍⚕️ 👩‍⚕️, 🧒 gồm 👦 👧…); cặp đôi,
  💃 🕺 vẫn riêng.
- Màu da gộp vào ô (chỉ bản một màu da cho cả emoji).

Mỗi ô một mục: ô có bản khác thì các hàng (theo giới tính) cách nhau `|`, trong hàng là bản gốc rồi 5 màu da,
cách nhau `,`. Hàng đầu, mục đầu là emoji hiện trong ô.
"""

import sys

GROUPS = {
    "Smileys & Emotion": "Mặt cười và biểu tượng cảm xúc",
    "People & Body": "Người",
    "Animals & Nature": "Động vật và thiên nhiên",
    "Food & Drink": "Thực phẩm và đồ uống",
    "Travel & Places": "Du lịch và địa danh",
    "Activities": "Hoạt động và sự kiện",
    "Objects": "Đồ vật",
    "Symbols": "Biểu tượng",
    "Flags": "Cờ",
}
# Các nhóm con của "People & Body" mà Gboard xếp vào nhóm mặt cười.
TO_SMILEYS = {
    "hand-fingers-open", "hand-fingers-partial", "hand-single-finger", "hand-fingers-closed",
    "hands", "hand-prop", "body-parts", "person-symbol",
}
# Thứ tự các nhóm con trong nhóm người của Gboard: cử chỉ, hoạt động, tưởng tượng, nghề, người, cặp đôi.
PEOPLE_ORDER = [
    "person-gesture", "person-activity", "person-sport", "person-resting", "person-fantasy", "person-role",
    "person", "family",
]
SKINS = set(range(0x1F3FB, 0x1F400))
ZWJ, MALE, FEMALE, PERSON, MAN, WOMAN, RIGHT = 0x200D, 0x2642, 0x2640, 0x1F9D1, 0x1F468, 0x1F469, 0x27A1
# Bộ ba không theo mẫu ZWJ: bản nam / nữ → bản trung tính.
NEUTRAL = {
    0x1F466: 0x1F9D2, 0x1F467: 0x1F9D2,  # 👦 👧 → 🧒
    0x1F468: 0x1F9D1, 0x1F469: 0x1F9D1,  # 👨 👩 → 🧑
    0x1F474: 0x1F9D3, 0x1F475: 0x1F9D3,  # 👴 👵 → 🧓
    0x1F934: 0x1FAC5, 0x1F478: 0x1FAC5,  # 🤴 👸 → 🫅
    0x1F930: 0x1FAC4, 0x1FAC3: 0x1FAC4,  # 🤰 🫃 → 🫄
}
SANTA = {(0x1F385,), (0x1F936,)}  # 🎅 🤶 → 🧑‍🎄
MX_CLAUS = (PERSON, ZWJ, 0x1F384)


def key(cps):
    return tuple(c for c in cps if c not in SKINS and c != 0xFE0F)


def neutral(k):
    """Khoá của bản gốc mà emoji [k] gộp vào (có thể chưa có trong danh sách): bỏ hướng quay phải (`➡`), bỏ giới tính."""
    if len(k) > 2 and k[-2] == ZWJ and k[-1] == RIGHT:
        return k[:-2]
    if len(k) > 2 and k[-2] == ZWJ and k[-1] in (MALE, FEMALE):
        return k[:-2]
    if len(k) > 2 and k[0] in (MAN, WOMAN) and k[1] == ZWJ:
        return (PERSON,) + k[1:]
    if len(k) == 1 and k[0] in NEUTRAL:
        return (NEUTRAL[k[0]],)
    if k in SANTA:
        return MX_CLAUS
    return None


def main(src, dst):
    groups = {name: [] for name in GROUPS.values()}
    tones = {}
    group = subgroup = None
    for line in open(src, encoding="utf-8"):
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
        elif line.startswith("# subgroup:"):
            subgroup = line.split(":", 1)[1].strip()
        elif group in GROUPS and "; fully-qualified" in line:
            name = line.split("#", 1)[1].split(" ", 3)[-1].strip()
            if name.startswith("family"):
                continue
            cps = [int(x, 16) for x in line.split(";")[0].split()]
            skin = [c for c in cps if c in SKINS]
            text = "".join(map(chr, cps))
            if skin:
                if len(set(skin)) == 1:
                    tones.setdefault(key(cps), []).append(text)
                continue
            target = GROUPS["Smileys & Emotion"] if subgroup in TO_SMILEYS else GROUPS[group]
            groups[target].append((key(cps), text, subgroup))
    people = groups[GROUPS["People & Body"]]
    people.sort(key=lambda e: PEOPLE_ORDER.index(e[2]) if e[2] in PEOPLE_ORDER else len(PEOPLE_ORDER))
    groups = {name: [(k, t) for k, t, _ in items] for name, items in groups.items()}

    keys = {k for items in groups.values() for k, _ in items}

    def root(k):
        # Đi tới bản gốc xa nhất có trong danh sách (`🚶‍♂️‍➡️` → `🚶‍♂️` → `🚶`).
        while (n := neutral(k)) in keys:
            k = n
        return k

    # Bản nam / nữ / quay phải có bản gốc thì thành hàng thêm trong ô của bản gốc.
    members = {}
    for items in groups.values():
        for k, _ in items:
            r = root(k)
            if r != k:
                members.setdefault(r, []).append(k)
    merged = {k for ks in members.values() for k in ks}
    text_of = {k: t for items in groups.values() for k, t in items}

    def row(k):
        t = tones.get(k, [])
        return [text_of[k]] + (t if len(t) == 5 else [])

    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Tạo bằng tools/emoji/gen_emoji.py từ emoji-test.txt của Unicode 15.1; nhóm và gộp ô như Gboard.\n")
        f.write("# Ô có bản khác: các hàng (giới tính) cách nhau |, trong hàng là bản gốc rồi 5 màu da, cách nhau dấu phẩy.\n")
        for name, items in groups.items():
            cells = []
            for k, _ in items:
                if k in merged:
                    continue
                rows = [row(k)] + [row(m) for m in members.get(k, [])]
                cells.append("|".join(",".join(r) for r in rows))
            f.write("=" + name + "\n" + " ".join(cells) + "\n")
            sys.stdout.buffer.write(f"{len(cells):5d} {name}\n".encode("utf-8"))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
