"""Tạo mobile/android/app/src/main/assets/emoji.txt theo đúng nhóm và thứ tự bảng emoji của Gboard.

    python -I tools/emoji/gen_emoji_gboard.py <thư mục res của Gboard> mobile/android/app/src/main/assets/emoji.txt

<thư mục res của Gboard>: các tệp `res/*.csv` giải nén từ base.apk của Gboard trên máy (tên tệp bị đổi ngẫu nhiên
mỗi phiên bản; nhận ra nhóm theo emoji đầu tệp). Các tệp này của Google, không đưa vào repo; chỉ lấy nhóm và thứ tự.

    adb shell pm path com.google.android.inputmethod.latin     # đường dẫn base.apk
    adb pull <đường dẫn>/base.apk && unzip base.apk 'res/*.csv' -d gboard

Mỗi dòng tệp Gboard là một ô: emoji hiện trong ô, rồi các bản khác (bản gốc + 5 màu da, theo từng hàng trung tính /
nữ / nam, có hướng ➡ thì thêm hàng). Cặp đôi có 25 tổ hợp hai màu da: hàng đầu là bản gốc + 5 bản cùng màu da, sau đó
mỗi màu da của người thứ nhất một hàng 5 bản. Ghi ra định dạng của emoji.txt: các hàng cách nhau `|`, trong hàng cách
nhau `,`; hàng đầu, mục đầu là emoji hiện trong ô.
"""

import io
import os
import sys

# Emoji đầu tệp → tên nhóm (đúng tên trong EmojiPanel.TAB_GROUPS), theo thứ tự các nhóm của Gboard.
GROUPS = [
    ("😀", "Mặt cười và biểu tượng cảm xúc"),
    ("🙇,🙇", "Người"),  # bản gộp giới tính (ô là người trung tính), như Gboard đang hiện
    ("💐", "Động vật và thiên nhiên"),
    ("🍓", "Thực phẩm và đồ uống"),
    ("🛑", "Du lịch và địa danh"),
    ("🎉", "Hoạt động và sự kiện"),
    ("📱", "Đồ vật"),
    ("🔴", "Biểu tượng"),
    ("🏁", "Cờ"),
]


def rows_of(fields):
    """Một dòng tệp Gboard → các hàng của ô."""
    shown, variants = fields[0], fields[1:]
    if not variants:
        return [[shown]]
    if len(variants) == 26:
        base, grid = variants[0], variants[1:]
        return [[base] + [grid[i * 5 + i] for i in range(5)]] + [grid[i * 5:(i + 1) * 5] for i in range(5)]
    if len(variants) % 6 == 0:
        rows = [variants[i:i + 6] for i in range(0, len(variants), 6)]
    else:
        rows = [variants]
    if rows[0][0] != shown:
        rows[0].insert(0, shown)
    return rows


def main(src, dst):
    files = {}
    for name in os.listdir(src):
        if not name.endswith(".csv"):
            continue
        first = io.open(os.path.join(src, name), encoding="utf-8").readline().strip()
        for head, group in GROUPS:
            if first == head or (first.startswith(head) and "," in head):
                files[group] = os.path.join(src, name)
    missing = [g for _, g in GROUPS if g not in files]
    if missing:
        sys.exit(f"Thiếu tệp nhóm: {missing}")
    with open(dst, "w", encoding="utf-8", newline="\n") as out:
        out.write("# Tạo bằng tools/emoji/gen_emoji_gboard.py: nhóm và thứ tự đúng bảng emoji của Gboard.\n")
        out.write("# Ô có bản khác: các hàng (giới tính, hướng; cặp đôi: màu da người thứ nhất) cách nhau |, trong hàng"
                  " cách nhau dấu phẩy.\n")
        for _, group in GROUPS:
            cells = []
            for line in io.open(files[group], encoding="utf-8"):
                fields = [f for f in line.strip().split(",") if f]
                if fields:
                    cells.append("|".join(",".join(r) for r in rows_of(fields)))
            out.write("=" + group + "\n" + " ".join(cells) + "\n")
            sys.stdout.buffer.write(f"{len(cells):5d} {group}\n".encode("utf-8"))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
