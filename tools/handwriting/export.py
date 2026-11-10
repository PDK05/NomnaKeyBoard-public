"""Xuất mô hình sang định dạng `hw.bin` cho crate Rust `handwriting` (xem handwriting/src/net.rs).

    python export.py D:/hannom-data/runs/v1/last.pt D:/hannom-data/hw.bin
    python export.py --fixture ../../handwriting/tests/fixture.bin
"""

import struct
import sys

import torch
import torch.nn as nn
import torch.nn.functional as F


def fold(w, bn, bias=None):
    """Gộp BatchNorm vào trọng số phía trước nó."""
    g = bn.weight / torch.sqrt(bn.running_var + bn.eps)
    b = (bias if bias is not None else torch.zeros_like(bn.running_mean)) - bn.running_mean
    return w * g.view(-1, *[1] * (w.dim() - 1)), b * g + bn.bias


def quant(w):
    """int8 theo từng kênh ra → (q, tỉ lệ)."""
    flat = w.reshape(w.shape[0], -1)
    scale = flat.abs().amax(1).clamp(min=1e-8) / 127
    q = torch.round(flat / scale[:, None]).clamp(-127, 127).to(torch.int8)
    return q, scale


def layers_of(model):
    """Net (trainer.py) → [(loại, ...)] với trọng số float đã gộp BN."""
    out = []
    mods = list(model.features)
    i = 0
    while i < len(mods):
        m = mods[i]
        if isinstance(m, nn.Conv2d):
            w, b = fold(m.weight, mods[i + 1], m.bias)
            out.append(("conv", w, b))
            i += 3
        elif isinstance(m, nn.MaxPool2d):
            out.append(("max",))
            i += 1
        elif isinstance(m, nn.AdaptiveAvgPool2d):
            out.append(("avg",))
            i += 1
        else:
            raise ValueError(m)
    lin, bn = model.embed[1], model.embed[2]
    w, b = fold(lin.weight, bn)
    out.append(("dense", w, b, True))
    out.append(("dense", model.head.weight, model.head.bias, False))
    return out


def dequantized(layers):
    """Thay trọng số bằng bản đã qua int8 (để đo đúng độ chính xác của tệp xuất)."""
    out = []
    for l in layers:
        if l[0] in ("conv", "dense"):
            q, s = quant(l[1])
            w = (q.float() * s[:, None]).reshape(l[1].shape)
            out.append((l[0], w, *l[2:]))
        else:
            out.append(l)
    return out


def run(layers, x):
    for l in layers:
        if l[0] == "conv":
            x = F.relu(F.conv2d(x, l[1], l[2], padding=1))
        elif l[0] == "max":
            x = F.max_pool2d(x, 2)
        elif l[0] == "avg":
            x = F.avg_pool2d(x, 2)
        else:
            x = F.linear(x.flatten(1), l[1], l[2])
            if l[3]:
                x = F.relu(x)
    return x


def write(layers, chars, size):
    buf = bytearray(b"HNHW")
    buf += struct.pack("<III", 1, size, len(chars))
    buf += struct.pack(f"<{len(chars)}I", *map(ord, chars))
    buf += struct.pack("<I", len(layers))
    for l in layers:
        if l[0] == "conv":
            q, s = quant(l[1])
            buf += struct.pack("<BII", 1, l[1].shape[1], l[1].shape[0])
        elif l[0] == "dense":
            q, s = quant(l[1])
            buf += struct.pack("<BIIB", 4, l[1].shape[1], l[1].shape[0], int(l[3]))
        else:
            buf += struct.pack("<B", 2 if l[0] == "max" else 3)
            continue
        buf += q.numpy().tobytes()
        buf += s.float().numpy().astype("<f4").tobytes()
        buf += l[2].float().numpy().astype("<f4").tobytes()
    return bytes(buf)


def fixture(path):
    torch.manual_seed(7)
    layers = [
        ("conv", torch.randn(3, 1, 3, 3), torch.randn(3) * 0.1),
        ("max",),
        ("conv", torch.randn(4, 3, 3, 3) * 0.5, torch.randn(4) * 0.1),
        ("max",), ("max",), ("max",),
        ("avg",),
        ("dense", torch.randn(6, 16) * 0.3, torch.randn(6) * 0.1, True),
        ("dense", torch.randn(5, 6), torch.randn(5) * 0.1, False),
    ]
    chars = list("泪河喃𡨸𠊛")
    img = torch.rand(1, 1, 64, 64)
    model = write(layers, chars, 64)
    logits = run(dequantized(layers), img)[0]
    with open(path, "wb") as f:
        f.write(struct.pack("<I", len(model)) + model)
        f.write(img.numpy().astype("<f4").tobytes())
        f.write(logits.detach().numpy().astype("<f4").tobytes())
    print("fixture", len(model), "bytes, logits", logits.tolist())


@torch.no_grad()
def main():
    if sys.argv[1] == "--fixture":
        return fixture(sys.argv[2])
    from common import load_charset
    from synth import Composer
    from trainer import Net, val_set

    chars = load_charset()
    ck = torch.load(sys.argv[1], map_location="cpu")
    model = Net(len(chars))
    model.load_state_dict(ck["model"])
    model.eval()
    layers = layers_of(model)
    data = write(layers, chars, 64)
    open(sys.argv[2], "wb").write(data)
    print(f"{sys.argv[2]}: {len(data) / 1e6:.1f} MB, step {ck['step']}")

    xs, ys = val_set(Composer(), chars, n=2048, seed=321)
    for name, ls in (("float", layers), ("int8", dequantized(layers))):
        pred = torch.cat([run(ls, xs[i : i + 256]).topk(5, 1).indices for i in range(0, len(xs), 256)])
        t1 = (pred[:, 0] == ys).float().mean().item()
        t5 = (pred == ys[:, None]).any(1).float().mean().item()
        print(f"{name}: top1 {t1:.3f} top5 {t5:.3f}")


if __name__ == "__main__":
    main()
