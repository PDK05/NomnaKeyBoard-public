# Mô hình viết tay

Đặt mô hình nhận dạng chữ viết tay vào đây với tên `hw.bin` (khoảng 13 MB, có sẵn trong repo).
Khi build, bàn phím Android đóng gói nó vào `assets/models/hw.bin`. Thiếu tệp thì bàn phím viết tay
vẫn mở được nhưng không có gợi ý.

Sinh mô hình bằng `tools/handwriting/` (cần Python 3.12 + PyTorch có CUDA):

```sh
cd tools/handwriting
python charset.py          # bộ chữ: Unihan kVietnamese ∪ Make Me a Hanzi
python render_fonts.py     # ảnh chữ in từ các phông
python train.py --steps 30000 --batch 384 --workers 5 --name v1
python export.py $HANNOM_DATA/runs/v1/last.pt ../../models/hw.bin
```

Dữ liệu lớn nằm ở `$HANNOM_DATA` (mặc định `D:/hannom-data`; trong repo này là `data/`):
`raw/` (Unihan, Make Me a Hanzi `graphics.txt`/`dictionary.txt`, cjkvi-ids `ids.txt`),
`fonts/` (Jigmo, LXGW WenKai), `cache/`, `runs/`.
