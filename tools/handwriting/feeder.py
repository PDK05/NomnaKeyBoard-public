"""Tiến trình con sinh lô chữ viết tay giả. Không import torch: trên Windows mỗi tiến trình con
nạp lại module, và nạp DLL CUDA ở 10 tiến trình làm cạn bộ nhớ ảo."""

import random
from collections import deque
from multiprocessing import Pool

import numpy as np

_state = {}


def _init(labels):
    from common import load_charset
    from synth import Composer

    _state["composer"] = Composer()
    _state["chars"] = load_charset()
    _state["labels"] = labels


def _batch(args):
    from synth import sample

    seed, n = args
    rng = random.Random(seed)
    np.random.seed(seed % 2**31)
    xs = np.empty((n, 64, 64), dtype=np.uint8)
    ys = np.empty(n, dtype=np.int64)
    i = 0
    while i < n:
        y = rng.choice(_state["labels"])
        img = sample(_state["composer"], _state["chars"][y], rng)
        if img is not None:
            xs[i] = (img * 255).astype(np.uint8)
            ys[i] = y
            i += 1
    return xs, ys


def batches(labels, n, workers, seed=0):
    """Lặp vô hạn các lô (ảnh uint8 n×64×64, nhãn); luôn giữ sẵn 2 lô mỗi tiến trình con."""
    pool = Pool(workers, initializer=_init, initargs=(labels,))
    i = seed * 1_000_003
    pending = deque()
    while True:
        while len(pending) < 2 * workers:
            pending.append(pool.apply_async(_batch, ((i, n),)))
            i += 1
        yield pending.popleft().get()
