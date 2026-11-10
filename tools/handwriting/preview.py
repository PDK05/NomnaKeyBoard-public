"""Ghép lưới ảnh mẫu để xem bằng mắt: python preview.py chars.txt out.png

Danh sách chữ đọc từ tệp UTF-8 vì chữ Hán trên dòng lệnh Windows dễ bị hỏng mã.
"""

import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image

from synth import Composer, sample


def main():
    chars, out = Path(sys.argv[1]).read_text(encoding="utf-8").strip(), sys.argv[2]
    per = int(sys.argv[3]) if len(sys.argv) > 3 else 12
    composer = Composer()
    rng = random.Random(1)
    rows = []
    for c in chars:
        imgs = [sample(composer, c, rng, hand=i > 0) for i in range(per)]
        imgs = [im if im is not None else np.zeros((64, 64), np.float32) for im in imgs]
        rows.append(np.concatenate([np.pad(im, 1, constant_values=0.3) for im in imgs], 1))
    grid = np.concatenate(rows, 0)
    Image.fromarray(((1 - grid) * 255).astype(np.uint8)).save(out)


if __name__ == "__main__":
    main()
