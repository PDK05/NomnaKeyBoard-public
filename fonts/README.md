# Phông Hán Nôm

Khi build, bàn phím Android đóng gói các phông trong thư mục này vào `assets/fonts/`. Thiếu tệp thì ứng dụng
dùng phông của máy.

- `hannom.ttf`: **MiSans L3** (Xiaomi), phủ CJK mở rộng B–F (≈ 60 nghìn chữ, gồm phần lớn chữ Nôm), không có
  chữ Hán thông dụng và chữ Latin (các chữ đó lấy từ phông hệ thống). Giấy phép theo điều khoản MiSans của Xiaomi.
- `hannom-extra.ttf`: phông dự phòng, cắt từ Jigmo (CC0) bằng `tools/fonts/extra.py`: các chữ ngoài BMP mà
  MiSans thiếu (chữ Nôm mới ở CJK mở rộng G, H, I, J và vài chữ ở B–F), ~24 nghìn chữ, ~13 MB. Bàn phím dùng nó
  làm phông dự phòng (Android 10+).

**Tải phông** từ GitHub Release `fonts-v1`:

```sh
gh release download fonts-v1 -R PDK05/NomnaKeyBoard-public -D fonts
```

SHA-256: `hannom.ttf` 710bc52d…bdd844, `hannom-extra.ttf` 8f97a560…bd7914.
