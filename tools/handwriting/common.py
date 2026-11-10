"""Đường dẫn và bộ nạp dữ liệu dùng chung cho pipeline viết tay."""

import json
import os
import pickle
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
# Dữ liệu lớn (Unihan, Make Me a Hanzi, IDS, phông, ảnh dựng sẵn) không nằm trong repo.
DATA = Path(os.environ.get("HANNOM_DATA", "D:/hannom-data"))

WIN_FONTS = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts"
# (tệp, chỉ số trong .ttc). Phông in đủ kiểu: Tống/Minh, Hắc, Khải; Jigmo và MiSans L3 phủ chữ Nôm ở CJK mở rộng.
FONTS = [
    (WIN_FONTS / "simsun.ttc", 0),
    (WIN_FONTS / "SimsunExtG.ttf", 0),
    (WIN_FONTS / "mingliub.ttc", 0),
    (WIN_FONTS / "msjh.ttc", 0),
    (WIN_FONTS / "msmincho.ttc", 0),
    (WIN_FONTS / "msgothic.ttc", 0),
    (WIN_FONTS / "yumin.ttf", 0),
    (WIN_FONTS / "YuGothM.ttc", 0),
    (WIN_FONTS / "meiryo.ttc", 0),
    (WIN_FONTS / "malgun.ttf", 0),
    (DATA / "fonts/LXGWWenKai-Regular.ttf", 0),
    (DATA / "fonts/Jigmo.ttf", 0),
    (DATA / "fonts/Jigmo2.ttf", 0),
    (DATA / "fonts/Jigmo3.ttf", 0),
    (REPO / "fonts/hannom.ttf", 0),
]

CACHE = DATA / "cache"


def cjk(c):
    o = ord(c)
    return 0x3400 <= o <= 0x4DBF or 0x4E00 <= o <= 0x9FFF or 0x20000 <= o <= 0x323AF or c == "〇"


def font_cmaps():
    """{(đường dẫn, chỉ số): tập mã chữ phông có}, lưu đệm vì đọc phông 30 MB khá chậm."""
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / "cmaps.pkl"
    if path.exists():
        return pickle.loads(path.read_bytes())
    from fontTools.ttLib import TTFont

    out = {}
    for f, i in FONTS:
        if f.exists():
            out[(str(f), i)] = set(TTFont(f, fontNumber=i, lazy=True).getBestCmap())
    path.write_bytes(pickle.dumps(out))
    return out


def load_mmah():
    """(nét, phân tách) của Make Me a Hanzi: {chữ: trung tuyến các nét}, {chữ: bản ghi dictionary}."""
    graphics, dictionary = {}, {}
    for line in open(DATA / "raw/mmah-graphics.txt", encoding="utf-8"):
        g = json.loads(line)
        if cjk(g["character"]):
            graphics[g["character"]] = g["medians"]
    for line in open(DATA / "raw/mmah-dictionary.txt", encoding="utf-8"):
        d = json.loads(line)
        dictionary[d["character"]] = d
    return graphics, dictionary


def load_ids():
    """{chữ: chuỗi IDS đầu tiên} từ cjkvi-ids, bỏ nhãn vùng như [GTJ]."""
    out = {}
    for line in open(DATA / "raw/ids.txt", encoding="utf-8"):
        if line.startswith("#") or line.startswith(";"):
            continue
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        ids = parts[2].split("[")[0]
        if ids and ids != parts[1]:
            out[parts[1]] = ids
    return out


def load_charset():
    return (DATA / "charset.txt").read_text(encoding="utf-8").split()
