"""Tính độ phổ biến cho dict/vi-dict.txt, dict/en-dict.txt, dict/nomna-dict.txt từ văn bản thật.

Nguồn (không nằm trong repo, đặt ở $HANNOM_DATA/raw/corpus, mặc định D:/hannom-data):
- Leipzig Corpora Collection (CC BY 4.0), https://wortschatz-leipzig.de/en/download:
  vie_news_2022_1M, eng_news_2023_1M, zho-trad_newscrawl_2011_300K (chỉ cần tệp *-sentences.txt).
- FrequencyWords (phụ đề OpenSubtitles, CC BY-SA 4.0), https://github.com/hermitdave/FrequencyWords:
  content/2018/vi/vi_full.txt, content/2018/en/en_full.txt — văn nói, bù cho báo chí.
- Unihan (raw/unihan): nguồn chữ Việt Nam (kIRG_VSource) và chữ giản thể (kTraditionalVariant).

Chạy: python tools/wordfreq/build.py  (vài phút, cần ~2 GB RAM).
"""

import math
import os
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DICT = REPO / "dict"
DATA = Path(os.environ.get("HANNOM_DATA", "D:/hannom-data"))
RAW = DATA / "raw"
CORPUS = RAW / "corpus"

TOKEN = re.compile(r"\w+(?:'\w+)*|\S")
TONES = {"\u0300": "2", "\u0301": "1", "\u0303": "4", "\u0309": "3", "\u0323": "5"}


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def canon(word: str) -> str:
    """Khoá so khớp tiếng Việt bất kể kiểu bỏ dấu (hoà = hòa, thuỷ = thủy): chữ thường, bỏ dấu thanh, thanh ghi cuối âm tiết."""
    out = []
    for syl in word.lower().split(" "):
        tone = "0"
        base = []
        for ch in unicodedata.normalize("NFD", syl):
            if ch in TONES:
                tone = TONES[ch]
            else:
                base.append(ch)
        out.append(unicodedata.normalize("NFC", "".join(base)) + tone)
    return " ".join(out)


def sentences(name: str):
    path = CORPUS / name / f"{name}-sentences.txt"
    with open(path, encoding="utf-8") as f:
        for line in f:
            _, _, s = line.rstrip("\n").partition("\t")
            yield unicodedata.normalize("NFC", s.replace("\u2019", "'"))


def chunks(s: str):
    """Các đoạn từ liền nhau trong câu (dấu câu, số, chữ dính số như `19th`… cắt đoạn), từ viết thường."""
    run = []
    for m in TOKEN.finditer(s):
        t = m.group()
        if t.replace("'", "").isalpha():
            run.append(t.lower())
        elif run:
            yield run
            run = []
    if run:
        yield run


def read_dict(path: Path):
    """(dòng chú thích đầu tệp, các dòng mục chia theo `;`)."""
    header, rows = [], []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not rows and (line.startswith("#") or not line.strip()):
            header.append(line)
        elif line.strip() and not line.startswith("#"):
            rows.append(line.split(";"))
    return header, rows


def subtitle_counts(name: str, key) -> Counter:
    c = Counter()
    with open(CORPUS / name, encoding="utf-8") as f:
        for line in f:
            w, _, n = line.rstrip("\n").rpartition(" ")
            if w and n.isdigit():
                c[key(unicodedata.normalize("NFC", w.replace("\u2019", "'")))] += int(n)
    return c


def per_million(c: Counter) -> dict:
    total = sum(c.values()) or 1
    return {k: v * 1e6 / total for k, v in c.items()}


def score(pm: float) -> int:
    """Độ phổ biến ghi vào từ điển gợi ý: 1000·log10(1 + số lần trên một tỉ từ)."""
    return round(1000 * math.log10(1 + pm * 1000))


def count_corpus(name: str, key, phrases: set, max_n: int):
    """Đếm từ đơn và cụm có trong `phrases` (2..max_n từ)."""
    uni, multi = Counter(), Counter()
    for i, s in enumerate(sentences(name)):
        if i % 200000 == 0:
            log(f"  {name}: {i}")
        for run in chunks(s):
            keys = [key(t) for t in run]
            uni.update(keys)
            for n in range(2, max_n + 1):
                for j in range(len(keys) - n + 1):
                    p = " ".join(keys[j : j + n])
                    if p in phrases:
                        multi[p] += 1
    return uni, multi


def build_vi(nomna_rows):
    header, rows = read_dict(DICT / "vi-dict.txt")
    words = [r[0] for r in rows]
    phrases = {canon(w) for w in words if " " in w} | {canon(r[1]) for r in nomna_rows if " " in r[1]}
    max_n = max(k.count(" ") + 1 for k in phrases)
    log("vi: đếm báo")
    uni, multi = count_corpus("vie_news_2022_1M", canon, phrases, max_n)
    subs = per_million(subtitle_counts("vi_full.txt", canon))
    news = per_million(uni)
    # Cụm nhiều chữ tính trên cùng mẫu số với chữ đơn của báo.
    total = sum(uni.values())
    phrase_pm = {k: v * 1e6 / total for k, v in multi.items()}

    def pm(k: str) -> float:
        if " " in k:
            return phrase_pm.get(k, 0.0)
        return (news.get(k, 0.0) + subs.get(k, 0.0)) / 2

    out = [
        "# Từ điển gợi ý tiếng Việt (bàn phím Quốc ngữ) – mỗi dòng: từ;độ phổ biến",
        "# Lấy từ âm đọc của nomna-dict.txt (bỏ chữ Hán Nôm). Độ phổ biến = 1000·log10(1 + số lần trên một tỉ từ),",
        "# đếm trên báo (Leipzig vie_news_2022_1M) và phụ đề (FrequencyWords) bằng tools/wordfreq/build.py.",
        "",
    ]
    out += [f"{w};{score(pm(canon(w)))}" for w in words]
    (DICT / "vi-dict.txt").write_text("\n".join(out) + "\n", encoding="utf-8")
    return pm


def build_en():
    header, rows = read_dict(DICT / "en-dict.txt")
    # Tệp gốc chỉ có từ; mỗi dòng một từ.
    words = [r[0] for r in rows]
    log("en: đếm báo")
    uni, _ = count_corpus("eng_news_2023_1M", str.lower, set(), 1)
    subs = per_million(subtitle_counts("en_full.txt", str.lower))
    news = per_million(uni)

    def pm(w: str) -> float:
        k = w.lower()
        return (news.get(k, 0.0) + subs.get(k, 0.0)) / 2

    out = [
        "# Từ điển gợi ý tiếng Anh – mỗi dòng: từ;độ phổ biến",
        "# Danh sách từ sinh từ English (American).dic + .aff (Hunspell). Độ phổ biến = 1000·log10(1 + số lần trên",
        "# một tỉ từ), đếm trên báo (Leipzig eng_news_2023_1M) và phụ đề (FrequencyWords) bằng tools/wordfreq/build.py.",
        "",
    ]
    out += [f"{w};{score(pm(w))}" for w in words]
    (DICT / "en-dict.txt").write_text("\n".join(out) + "\n", encoding="utf-8")


def unihan():
    vtier, simplified = {}, set()
    tiers = {"V0": 4, "V1": 3, "V2": 2, "V3": 1.5, "V4": 1, "VN": 1}
    with open(RAW / "unihan/Unihan_IRGSources.txt", encoding="utf-8") as f:
        for line in f:
            p = line.split("\t")
            if len(p) >= 3 and p[1] == "kIRG_VSource":
                vtier[chr(int(p[0][2:], 16))] = tiers.get(p[2][:2], 1)
    with open(RAW / "unihan/Unihan_Variants.txt", encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 3 and p[1] == "kTraditionalVariant":
                ch = chr(int(p[0][2:], 16))
                trad = {chr(int(u[2:], 16)) for u in p[2].split()}
                if ch not in trad:
                    simplified.add(ch)
    return vtier, simplified


def build_nomna(vi_pm):
    path = DICT / "nomna-dict.txt"
    header, rows = read_dict(path)
    rows = [r for r in rows if len(r) >= 2]
    vtier, simplified = unihan()

    log("zh: đếm chữ và từ")
    texts = {r[0] for r in rows if len(r[0]) > 1}
    max_len = min(4, max(len(t) for t in texts))
    zh = Counter()
    for i, s in enumerate(sentences("zho-trad_newscrawl_2011_300K")):
        zh.update(s)
        for n in range(2, max_len + 1):
            for j in range(len(s) - n + 1):
                t = s[j : j + n]
                if t in texts:
                    zh[t] += 1
    zh_total = sum(v for k, v in zh.items() if len(k) == 1)
    zh_pm = {k: v * 1e6 / zh_total for k, v in zh.items()}

    # Bằng chứng Hán Việt: chữ c đọc r nằm trong cụm có âm đọc hay gặp trong tiếng Việt (學 trong 學生 học sinh).
    evidence = Counter()
    for text, reading, *_ in rows:
        syl = reading.split(" ")
        if len(text) > 1 and len(text) == len(syl):
            w = vi_pm(canon(reading))
            for c, r in zip(text, syl):
                evidence[(c, r)] += w
    readings_of = defaultdict(list)
    for text, reading, *_ in rows:
        if len(text) == 1:
            readings_of[text].append(reading)

    def char_score(c: str, r: str) -> float:
        ev = evidence[(c, r)]
        rs = readings_of[c]
        share = (ev + 1) / sum(evidence[(c, x)] + 1 for x in rs)
        s = math.log10(1 + ev) + 0.7 * math.log10(1 + zh_pm.get(c, 0) * share) + 0.5 * vtier.get(c, 0)
        return s - (2 if c in simplified else 0)

    def phrase_score(t: str) -> float:
        return math.log10(1 + zh_pm.get(t, 0)) + 0.3 * sum(vtier.get(c, 0) for c in t) / len(t) - (
            2 if any(c in simplified for c in t) else 0
        )

    # Trong cùng một âm: xếp theo điểm, xác suất theo hạng kiểu Zipf; nhân với độ hay gặp của âm đó trong tiếng Việt.
    groups = defaultdict(list)
    for i, (text, reading, *_) in enumerate(rows):
        groups[reading].append(i)
    weight = [0.0] * len(rows)
    rank_in_group = {}
    for reading, idx in groups.items():
        sc = {i: (char_score(rows[i][0], reading) if len(rows[i][0]) == 1 else phrase_score(rows[i][0])) for i in idx}
        ordered = sorted(idx, key=lambda i: (-sc[i], i))
        z = [1 / (k + 1) ** 1.3 for k in range(len(ordered))]
        zs = sum(z)
        base = vi_pm(canon(reading)) + 0.01
        for k, i in enumerate(ordered):
            weight[i] = base * z[k] / zs
            rank_in_group[i] = k
    # Bộ gõ cộng một phần nghìn số mục mỗi lần chọn và giữ tổng xấp xỉ số mục: lấy căn để chữ hay gặp không
    # trội quá, chọn vài lần là chữ khác lên được; rồi chia tỉ lệ cho tổng bằng số mục.
    flat = [math.sqrt(w) for w in weight]
    k = len(rows) / sum(flat)
    freqs = [round(f * k) for f in flat]

    # Giữ thứ tự âm của tệp, trong mỗi âm xếp theo điểm (chữ cùng độ phổ biến thì thứ tự tệp phân định).
    order = sorted(range(len(rows)), key=lambda i: (min(groups[rows[i][1]]), rank_in_group[i]))
    out = [
        "# Từ điển mặc định Nôm Na – mỗi dòng: chữ;âm đọc;độ phổ biến (chữ nhiều âm thì mỗi âm một dòng)",
        "# Độ phổ biến tính trên số mục của từ điển (tổng xấp xỉ số mục); bộ gõ tự tăng khi chọn chữ.",
        "# Ước từ độ hay gặp của âm trong tiếng Việt, cụm Hán Việt chứa chữ, tần suất chữ Hán phồn thể và nguồn",
        "# chữ Việt Nam trong Unihan – sinh bởi tools/wordfreq/build.py.",
        "",
    ]
    out += [f"{rows[i][0]};{rows[i][1]};{freqs[i]}" for i in order]
    path.write_text("\n".join(out) + "\n", encoding="utf-8")
    log(f"  nomna: {len(rows)} mục, tổng {sum(freqs)}, lớn nhất {max(freqs)}")


def main():
    _, nomna_rows = read_dict(DICT / "nomna-dict.txt")
    vi_pm = build_vi([r for r in nomna_rows if len(r) >= 2])
    build_nomna(vi_pm)
    build_en()


if __name__ == "__main__":
    main()
