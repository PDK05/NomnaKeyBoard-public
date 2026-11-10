# Phông Hán Nôm

Khi build, bàn phím Android đóng gói các phông trong thư mục này vào `assets/fonts/`. Thiếu tệp thì ứng dụng
dùng phông của máy.

- `hannom.ttf`: phông chính (tuỳ chọn, không phát hành ở repo này). Đặt vào đây một phông phủ CJK mở rộng B–F
  nếu có.
- `hannom-extra.ttf`: phông dự phòng, cắt từ Jigmo (CC0) bằng `tools/fonts/extra.py`: các chữ ngoài BMP
  (gồm chữ Nôm mới ở CJK mở rộng G, H, I, J) mà phông chính thiếu, ~24 nghìn chữ, ~13 MB. Bàn phím dùng nó làm
  phông dự phòng (Android 10+).

**Tải phông dự phòng** từ GitHub Release `fonts-v1`:

```sh
gh release download fonts-v1 -R PDK05/NomnaKeyBoard-public -D fonts
```

SHA-256 `hannom-extra.ttf`: 8f97a560…bd7914.
