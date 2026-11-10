"""Mô hình và vòng huấn luyện CNN nhận dạng chữ viết tay theo ảnh (không phụ thuộc thứ tự nét).

Mỗi lô: ~60% chữ viết tay giả dựng từ nét (synth.py, sinh liên tục trong các tiến trình con của feeder.py)
và ~40% chữ in từ phông (cache/fonts.npz) được bóp méo trên GPU. Chạy qua train.py.
"""

import argparse
import math
import random
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from common import CACHE, DATA, load_charset
from feeder import batches
from synth import Composer, sample


def block(i, o):
    return [nn.Conv2d(i, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True)]


class Net(nn.Module):
    """64×64 → 4×4×384 → gộp trung bình 2×2 (giữ bố cục thô) → 384 → lớp. ≈150 triệu phép nhân-cộng."""

    def __init__(self, classes):
        super().__init__()
        pool = nn.MaxPool2d(2)
        self.features = nn.Sequential(
            *block(1, 32), pool,
            *block(32, 64), pool,
            *block(64, 128), *block(128, 128), pool,
            *block(128, 256), *block(256, 256), pool,
            *block(256, 384),
            nn.AdaptiveAvgPool2d(2),
        )
        self.embed = nn.Sequential(nn.Flatten(), nn.Linear(1536, 384, bias=False), nn.BatchNorm1d(384), nn.ReLU(inplace=True))
        self.drop = nn.Dropout(0.2)
        self.head = nn.Linear(384, classes)

    def forward(self, x):
        return self.head(self.drop(self.embed(self.features(x))))


def warp(x, gen=None):
    """Bóp méo ảnh chữ in cho giống viết tay: nghiêng, xô, co giãn, cong, đậm/nhạt nét."""
    n, dev = x.shape[0], x.device
    a = (torch.rand(n, device=dev) - 0.5) * math.radians(20)
    sh = (torch.rand(n, device=dev) - 0.5) * 0.4
    sx = 0.9 + torch.rand(n, device=dev) * 0.25
    sy = 0.9 + torch.rand(n, device=dev) * 0.25
    t = (torch.rand(n, 2, device=dev) - 0.5) * 0.08
    cos, sin = torch.cos(a), torch.sin(a)
    theta = torch.stack([
        torch.stack([cos * sx, -sin * sy + sh, t[:, 0]], 1),
        torch.stack([sin * sx, cos * sy, t[:, 1]], 1),
    ], 1)
    grid = F.affine_grid(theta, x.shape, align_corners=False)
    # Trường cong mượt: nhiễu 4×4 phóng lên 64×64.
    flow = F.interpolate(torch.randn(n, 2, 4, 4, device=dev) * 0.03, size=x.shape[-2:], mode="bicubic", align_corners=False)
    grid = grid + flow.permute(0, 2, 3, 1)
    x = F.grid_sample(x, grid, align_corners=False, padding_mode="zeros")
    # Nét đậm hơn với một phần ảnh (phông Tống/Minh nét mảnh hơn bút).
    thick = F.max_pool2d(x, 3, 1, 1)
    mask = (torch.rand(n, 1, 1, 1, device=dev) < 0.45).float()
    return mask * thick + (1 - mask) * x


def val_set(composer, chars, n=4096, seed=123):
    rng = random.Random(seed)
    np.random.seed(seed)
    xs, ys = [], []
    while len(xs) < n:
        y = rng.randrange(len(chars))
        img = sample(composer, chars[y], rng)
        if img is not None:
            xs.append(img)
            ys.append(y)
    return torch.tensor(np.stack(xs))[:, None], torch.tensor(ys)


@torch.no_grad()
def evaluate(model, xs, ys, dev):
    model.eval()
    top1 = top5 = 0
    for i in range(0, len(xs), 512):
        out = model(xs[i : i + 512].to(dev))
        best = out.topk(5, 1).indices.cpu()
        y = ys[i : i + 512, None]
        top1 += (best[:, :1] == y).sum().item()
        top5 += (best == y).any(1).sum().item()
    model.train()
    return top1 / len(xs), top5 / len(xs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", type=int, default=40000)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--workers", type=int, default=10)
    ap.add_argument("--name", default="v1")
    ap.add_argument("--resume", default="")
    # Khởi đầu từ mô hình đã huấn luyện với bộ chữ cũ (là phần đầu của bộ chữ hiện tại).
    ap.add_argument("--init", default="")
    args = ap.parse_args()

    dev = torch.device("cuda")
    torch.backends.cudnn.benchmark = True
    chars = load_charset()
    composer = Composer()
    synth_labels = [i for i, c in enumerate(chars) if composer.can_compose(c)]

    fonts = np.load(CACHE / "fonts.npz")
    f_imgs = torch.from_numpy(fonts["imgs"])
    f_labels = fonts["labels"]
    rows = {}
    for r, l in enumerate(f_labels):
        rows.setdefault(int(l), []).append(r)
    font_classes = list(rows)

    n_synth = int(args.batch * 0.6)
    n_font = args.batch - n_synth
    loader = batches(synth_labels, n_synth, args.workers, seed=int(time.time()))

    vx, vy = val_set(composer, chars)
    model = Net(len(chars)).to(dev).to(memory_format=torch.channels_last)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.05)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=args.steps, pct_start=0.05)
    start = 0
    out_dir = DATA / "runs" / args.name
    out_dir.mkdir(parents=True, exist_ok=True)
    if args.resume:
        ck = torch.load(args.resume, map_location=dev)
        model.load_state_dict(ck["model"])
        opt.load_state_dict(ck["opt"])
        sched.load_state_dict(ck["sched"])
        start = ck["step"]
    elif args.init:
        old = torch.load(args.init, map_location=dev)["model"]
        state = model.state_dict()
        for k, v in old.items():
            if not k.startswith("head."):
                state[k] = v
        # Chữ mới bắt đầu là "lớp trung bình" của chữ cũ (cộng chút nhiễu để tách nhau): logit của
        # mô hình cũ nhỏ (≈ -1.5), hàng khởi tạo ngẫu nhiên kiểu mặc định sẽ lấn át chữ đúng.
        w, b, n = old["head.weight"], old["head.bias"], old["head.bias"].shape[0]
        state["head.weight"][:n] = w
        state["head.weight"][n:] = w.mean(0) + torch.randn_like(state["head.weight"][n:]) * w.std(0) * 0.1
        state["head.bias"][:n] = b
        state["head.bias"][n:] = b.mean()
        model.load_state_dict(state)
        print(f"init từ {args.init}: {old['head.bias'].shape[0]} → {len(chars)} chữ", flush=True)

    t0, seen_loss = time.time(), []
    for step in range(start, args.steps):
        sx, sy = next(loader)
        cls = np.random.choice(font_classes, n_font)
        fr = np.array([random.choice(rows[c]) for c in cls])
        fx = f_imgs[fr].to(dev, non_blocking=True).float()[:, None] / 255
        fx = warp(fx)
        sx = torch.from_numpy(sx).to(dev, non_blocking=True).float()[:, None] / 255
        x = torch.cat([sx, fx]).contiguous(memory_format=torch.channels_last)
        y = torch.cat([torch.from_numpy(sy), torch.from_numpy(cls).long()]).to(dev)
        with torch.autocast("cuda", dtype=torch.bfloat16):
            loss = F.cross_entropy(model(x), y, label_smoothing=0.1)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        opt.step()
        sched.step()
        seen_loss.append(loss.item())
        if (step + 1) % 200 == 0:
            rate = 200 * args.batch / (time.time() - t0)
            print(f"step {step + 1} loss {np.mean(seen_loss):.3f} lr {sched.get_last_lr()[0]:.2e} {rate:.0f} img/s", flush=True)
            t0, seen_loss = time.time(), []
        if (step + 1) % 2000 == 0 or step + 1 == args.steps:
            t1, t5 = evaluate(model, vx, vy, dev)
            print(f"== step {step + 1} val top1 {t1:.3f} top5 {t5:.3f}", flush=True)
            torch.save({"model": model.state_dict(), "opt": opt.state_dict(), "sched": sched.state_dict(),
                        "step": step + 1, "classes": len(chars)}, out_dir / "last.pt")


if __name__ == "__main__":
    main()
