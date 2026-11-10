"""Sinh chữ viết tay giả từ nét: dựng chữ (Make Me a Hanzi, ghép theo IDS), biến dạng như người viết, rồi vẽ ảnh.

Toạ độ nét: khung đơn vị [0, 1]², y hướng xuống. Ảnh ra 64×64, mực = 1, nền = 0, chuẩn hoá theo khung
bao mực giống hệt `handwriting::render` bên Rust (cùng lề, cùng cách vẽ đoạn thẳng có khử răng cưa).
"""

import math
import random

import numpy as np
from PIL import Image, ImageDraw

from common import load_ids, load_mmah

SIZE = 64
MARGIN = 4.0
# Nét viết lúc nhận dạng (Rust) luôn dày PEN px; lúc huấn luyện cho dao động quanh đó.
PEN = 2.5

OPS2 = set("⿰⿱⿴⿵⿶⿷⿸⿹⿺⿻")
OPS3 = set("⿲⿳")
# Ô của phần bên trong với các kiểu bao (phần ngoài chiếm cả khung).
INNER = {
    "⿴": (0.25, 0.25, 0.75, 0.75),
    "⿵": (0.25, 0.3, 0.75, 0.95),
    "⿶": (0.25, 0.05, 0.75, 0.7),
    "⿷": (0.3, 0.25, 0.95, 0.75),
    "⿸": (0.35, 0.35, 0.95, 0.95),
    "⿹": (0.05, 0.35, 0.65, 0.95),
    "⿺": (0.35, 0.05, 0.95, 0.7),
    "⿻": (0.0, 0.0, 1.0, 1.0),
}


def parse_ids(s):
    """Chuỗi IDS → cây: ('c', chữ) hoặc ('op', toán tử, [con]). Trả None nếu có phần không mã hoá."""

    def go(i):
        if i >= len(s):
            raise ValueError
        ch = s[i]
        if ch in OPS2 or ch in OPS3:
            kids, j = [], i + 1
            for _ in range(3 if ch in OPS3 else 2):
                k, j = go(j)
                kids.append(k)
            return ("op", ch, kids), j
        if ch in "？?{}①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳" or 0x2FF0 <= ord(ch) <= 0x2FFF or ord(ch) == 0x31EF:
            raise ValueError
        return ("c", ch), i + 1

    try:
        tree, end = go(0)
        return tree if end == len(s) else None
    except ValueError:
        return None


def to_unit(medians):
    """Trung tuyến Make Me a Hanzi (khung 1024, y hướng lên, gốc 900) → khung đơn vị."""
    return [np.array([[x / 1024.0, (900 - y) / 1024.0] for x, y in m], dtype=np.float32) for m in medians]


def bbox(strokes):
    pts = np.concatenate(strokes)
    return pts[:, 0].min(), pts[:, 1].min(), pts[:, 0].max(), pts[:, 1].max()


def place(strokes, src, dst):
    """Ánh xạ các nét từ ô src sang ô dst (x0, y0, x1, y1)."""
    sx = (dst[2] - dst[0]) / max(src[2] - src[0], 1e-3)
    sy = (dst[3] - dst[1]) / max(src[3] - src[1], 1e-3)
    off = np.array([dst[0], dst[1]], dtype=np.float32)
    o = np.array([src[0], src[1]], dtype=np.float32)
    scale = np.array([sx, sy], dtype=np.float32)
    return [(s - o) * scale + off for s in strokes]


def sub(box, frac):
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    return (x0 + frac[0] * w, y0 + frac[1] * h, x0 + frac[2] * w, y0 + frac[3] * h)


class Composer:
    """Dựng nét cho một chữ: lấy thẳng từ Make Me a Hanzi, hoặc ghép bộ kiện theo IDS.

    Bộ kiện ở một vị trí (vd. 氵 bên trái ⿰) lấy đúng hình dạng của nó trong một chữ có sẵn
    cùng vị trí (vd. 河), nên 氵 không bị vẽ thành 水 thu nhỏ.
    """

    def __init__(self):
        graphics, dictionary = load_mmah()
        self.graphics = {c: to_unit(m) for c, m in graphics.items()}
        self.ids = load_ids()
        # (toán tử, vị trí, bộ kiện) → [(nét đã chuẩn hoá về ô cha [0,1]², ô của bộ kiện trong ô cha)]
        self.lib = {}
        for c, d in dictionary.items():
            if c not in self.graphics:
                continue
            tree = parse_ids(d.get("decomposition", ""))
            if tree is not None and tree[0] == "op":
                self._collect(c, tree, [], d["matches"])
        self._ok = {}

    def _collect(self, c, node, path, matches):
        strokes = self.graphics[c]
        if path:
            mine = [s for s, m in zip(strokes, matches) if m and m[: len(path)] == path]
            if not mine:
                return
            region = bbox(mine)
        else:
            region = (0.0, 0.0, 1.0, 1.0)
        rw, rh = region[2] - region[0], region[3] - region[1]
        if rw < 0.05 or rh < 0.05:
            return
        for i, kid in enumerate(node[2]):
            p = path + [i]
            if kid[0] == "op":
                self._collect(c, kid, p, matches)
                continue
            mine = [s for s, m in zip(strokes, matches) if m and m[: len(p)] == p]
            if mine:
                norm = place(mine, region, (0, 0, 1, 1))
                self.lib.setdefault((node[1], i, kid[1]), []).append((norm, bbox(norm)))

    def can_compose(self, c, depth=0):
        if c in self._ok:
            return self._ok[c]
        ok = c in self.graphics
        if not ok and depth < 6 and c in self.ids:
            tree = parse_ids(self.ids[c])
            ok = tree is not None and tree[0] == "op" and self._tree_ok(tree, depth + 1)
        self._ok[c] = ok
        return ok

    def _tree_ok(self, node, depth):
        if node[0] == "c":
            return self.can_compose(node[1], depth)
        return all(self._tree_ok(k, depth) for k in node[2])

    def compose(self, c, rng=random, box=(0.0, 0.0, 1.0, 1.0), depth=0):
        """Danh sách nét (mảng N×2) của chữ c đặt trong ô box, hoặc None."""
        has_ids = c in self.ids and depth < 6
        if c in self.graphics and (not has_ids or depth > 0 or rng.random() < 0.75):
            return place(self.graphics[c], (0, 0, 1, 1), box)
        tree = parse_ids(self.ids[c]) if has_ids else None
        if tree is None or tree[0] != "op":
            return place(self.graphics[c], (0, 0, 1, 1), box) if c in self.graphics else None
        out = self._node(tree, box, rng, depth + 1)
        if out is None and c in self.graphics:
            return place(self.graphics[c], (0, 0, 1, 1), box)
        return out

    def _node(self, node, box, rng, depth):
        if node[0] == "c":
            return self.compose(node[1], rng, box, depth)
        op, kids = node[1], node[2]
        picks = []
        for i, k in enumerate(kids):
            cands = self.lib.get((op, i, k[1])) if k[0] == "c" else None
            picks.append(rng.choice(cands) if cands else None)
        split = op in ("⿰", "⿲", "⿱", "⿳")
        if split and any(p is None for p in picks):
            # Thiếu mẫu cho một con: chia khung theo độ phức tạp, đặt lại cả các con có mẫu vào ô mới
            # (tránh kiểu 𠊛 = ⿰㝵人 mà 人 lấy từ chữ khác chiếm gần hết khung).
            counts = [len(p[0]) if p else self.count(k) for k, p in zip(kids, picks)]
            boxes = self._split(op, counts)
            axis = 0 if op in ("⿰", "⿲") else 1
            out = []
            for k, pick, b in zip(kids, picks, boxes):
                if pick is not None:
                    dst = list(b)
                    dst[axis] += 0.02
                    dst[axis + 2] -= 0.02
                    dst[1 - axis], dst[3 - axis] = pick[1][1 - axis], pick[1][3 - axis]
                    out += place(pick[0], pick[1], sub(box, dst))
                    continue
                got = self._node(k, sub(box, b), rng, depth)
                if got is None:
                    return None
                out += got
            return out
        boxes = self._boxes(op, picks)
        out = []
        for k, pick, b in zip(kids, picks, boxes):
            if pick is not None:
                out += place(pick[0], (0, 0, 1, 1), box)
                continue
            got = self._node(k, sub(box, b), rng, depth)
            if got is None:
                return None
            out += got
        return out

    def count(self, node, depth=0):
        """Số nét ước lượng của chữ/cây IDS."""
        if isinstance(node, str):
            node = ("c", node)
        if node[0] == "op":
            return sum(self.count(k, depth) for k in node[2])
        c = node[1]
        if c in self.graphics:
            return len(self.graphics[c])
        tree = parse_ids(self.ids.get(c, "")) if depth < 6 else None
        return self.count(tree, depth + 1) if tree else 4

    @staticmethod
    def _split(op, counts):
        w = np.array([max(n, 1) ** 0.6 for n in counts], dtype=np.float64)
        f = w / w.sum()
        lo = 0.35 if len(counts) == 2 else 0.22
        f = np.clip(f, lo, 1 - lo * (len(counts) - 1))
        f /= f.sum()
        cuts = np.concatenate([[0.0], np.cumsum(f)])
        axis0 = op in ("⿰", "⿲")
        return [(a, 0.0, b, 1.0) if axis0 else (0.0, a, 1.0, b) for a, b in zip(cuts[:-1], cuts[1:])]

    @staticmethod
    def _boxes(op, picks):
        """Ô (theo phân số của ô cha) cho từng con chưa có mẫu, né phần mà các con có mẫu đã chiếm."""
        n = len(picks)
        if op in ("⿰", "⿲", "⿱", "⿳"):
            axis = 0 if op in ("⿰", "⿲") else 1
            cuts = [i / n for i in range(n + 1)]
            for i, p in enumerate(picks):
                if p is None:
                    continue
                lo, hi = p[1][axis], p[1][axis + 2]
                if i > 0:
                    cuts[i] = lo - 0.02
                if i < n - 1:
                    cuts[i + 1] = hi + 0.02
            out = []
            for i in range(n):
                a, b = cuts[i], max(cuts[i + 1], cuts[i] + 0.15)
                out.append((a, 0.0, b, 1.0) if axis == 0 else (0.0, a, 1.0, b))
            return out
        return [(0.0, 0.0, 1.0, 1.0), INNER.get(op, (0.2, 0.2, 0.8, 0.8))]


# ---------- biến dạng kiểu viết tay ----------


def length(s):
    return float(np.linalg.norm(np.diff(s, axis=0), axis=1).sum()) if len(s) > 1 else 0.0


def resample(s, step=0.05):
    """Chèn điểm để nét dài có đủ điểm cho nhiễu và làm mượt."""
    if len(s) < 2:
        return s
    out = [s[0]]
    for a, b in zip(s[:-1], s[1:]):
        n = max(1, int(np.linalg.norm(b - a) / step))
        for t in range(1, n + 1):
            out.append(a + (b - a) * (t / n))
    return np.array(out, dtype=np.float32)


def smooth(s, rounds=1):
    """Làm mượt Chaikin, giữ hai đầu nét."""
    for _ in range(rounds):
        if len(s) < 3:
            return s
        q = 0.75 * s[:-1] + 0.25 * s[1:]
        r = 0.25 * s[:-1] + 0.75 * s[1:]
        mid = np.empty((2 * len(q), 2), dtype=np.float32)
        mid[0::2], mid[1::2] = q, r
        s = np.concatenate([s[:1], mid[1:-1], s[-1:]])
    return s


def cursive(strokes, rng):
    """Phá cách như người viết nhanh: bỏ chấm, nối chuỗi chấm thành một nét, nối liền nét (thảo/hành)."""
    strokes = list(strokes)
    short = [i for i, s in enumerate(strokes) if length(s) < 0.13]
    # Bỏ một chấm (vd. ba chấm thuỷ chỉ viết hai).
    if short and len(strokes) > 3 and rng.random() < 0.15:
        strokes.pop(rng.choice(short))
    # Chuỗi nét ngắn liền nhau (氵, 灬, 忄...) gộp thành một nét: zíc zắc, hoặc duỗi thành gần như thẳng.
    if rng.random() < 0.3:
        out, i = [], 0
        while i < len(strokes):
            j = i
            while (
                j + 1 < len(strokes)
                and length(strokes[j]) < 0.16
                and length(strokes[j + 1]) < 0.2
                and np.linalg.norm(strokes[j][-1] - strokes[j + 1][0]) < 0.25
            ):
                j += 1
            if j > i and rng.random() < 0.7:
                run = np.concatenate(strokes[i : j + 1])
                if rng.random() < 0.35:
                    run = np.stack([run[0], run[len(run) // 2], run[-1]])
                out.append(run)
            else:
                out.extend(strokes[i : j + 1])
            i = j + 1
        strokes = out
    # Nối liền nét liên tiếp gần nhau (bút không nhấc).
    if rng.random() < 0.3:
        p = rng.uniform(0.15, 0.5)
        out = [strokes[0]]
        for s in strokes[1:]:
            if np.linalg.norm(out[-1][-1] - s[0]) < 0.35 and rng.random() < p:
                out[-1] = np.concatenate([out[-1], s])
            else:
                out.append(s)
        strokes = out
    return strokes


def distort(strokes, rng):
    """Nét lệch, run tay, chữ nghiêng/xô."""
    a = math.radians(rng.uniform(-9, 9))
    sh = rng.uniform(-0.18, 0.18)
    sx, sy = rng.uniform(0.85, 1.15), rng.uniform(0.85, 1.15)
    m = np.array([[math.cos(a), -math.sin(a)], [math.sin(a), math.cos(a)]], dtype=np.float32)
    m = m @ np.array([[sx, sh], [0, sy]], dtype=np.float32)
    jit = rng.uniform(0.002, 0.007)
    move = rng.uniform(0.0, 0.025)
    # Cong toàn chữ bằng một trường dịch chuyển hình sin nhẹ.
    wa, wf, wp = rng.uniform(0, 0.03), rng.uniform(1, 3), rng.uniform(0, 6.3)
    out = []
    for s in strokes:
        s = resample(s)
        s = s + np.float32(np.random.normal(0, move, 2))
        s = s + np.random.normal(0, jit, s.shape).astype(np.float32)
        s = smooth(s, 2)
        s = s @ m.T
        s[:, 0] += wa * np.sin(wf * s[:, 1] * 6.28 + wp)
        out.append(s.astype(np.float32))
    return out


# ---------- vẽ ----------


def normalize(strokes, size=SIZE, margin=MARGIN):
    """Co giãn khung bao mực vào ảnh size×size, giữ tỉ lệ, đặt giữa (giống Rust)."""
    x0, y0, x1, y1 = bbox(strokes)
    w, h = x1 - x0, y1 - y0
    s = (size - 2 * margin) / max(w, h, 1e-6)
    s = min(s, (size - 2 * margin) * 4)  # một chấm nhỏ không phóng to vô hạn
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    return [(st - np.array([cx, cy], dtype=np.float32)) * s + size / 2 for st in strokes]


SS = 4


def raster(strokes, pen=PEN, size=SIZE):
    """Vẽ nét dày `pen` px, đầu nét tròn; vẽ ở độ phân giải ×4 rồi thu nhỏ để khử răng cưa.

    Bên Rust vẽ bằng khoảng cách tới đoạn thẳng; hai cách cho ảnh gần như trùng nhau.
    """
    img = Image.new("L", (size * SS, size * SS), 0)
    d = ImageDraw.Draw(img)
    w = max(1, round(pen * SS))
    r = pen * SS / 2
    for s in strokes:
        pts = [(float(x) * SS, float(y) * SS) for x, y in s]
        if len(pts) > 1:
            d.line(pts, fill=255, width=w, joint="curve")
        for x, y in (pts[0], pts[-1]):
            d.ellipse((x - r, y - r, x + r, y + r), fill=255)
    img = img.resize((size, size), Image.BOX)
    return np.asarray(img, dtype=np.float32) / 255.0


def sample(composer, c, rng=random, hand=True):
    """Một ảnh 64×64 float32 của chữ c viết tay giả, hoặc None nếu không dựng được."""
    strokes = composer.compose(c, rng)
    if not strokes:
        return None
    if hand:
        strokes = cursive(strokes, rng)
        strokes = distort(strokes, rng)
    pen = rng.uniform(1.6, 3.6) if hand else PEN
    return raster(normalize(strokes), pen)
