# NomnaKeyBoard – Bàn phím Hán Nôm cho Android

Bộ gõ tiếng Việt kiểu Unikey cho Android, gõ được chữ Hán Nôm qua thanh gợi ý, có Telex đầy đủ / rút gọn,
viết tay, từ điển riêng dạng `chữ;âm đọc`. Đặc tả: [PRD-mobile.md](PRD-mobile.md).

## Cấu trúc

| Thư mục | Nội dung |
|---------|----------|
| `core/` | Lõi Rust: Telex, kiểm tra âm tiết, tách âm tiết, từ điển, xếp hạng gợi ý |
| `handwriting/` | Lõi Rust nhận dạng chữ viết tay (chạy mô hình `models/hw.bin`) |
| `android-ffi/` | Thư viện JNI (Rust) nối lõi với bàn phím Android |
| `mobile/android/` | Bàn phím Android kiểu Gboard + ứng dụng cài đặt, từ điển cá nhân (Kotlin, View thuần) |
| `dict/` | Từ điển: Hán Nôm `nomna-dict.txt` (`chữ;âm;độ phổ biến`), gợi ý từ `vi-dict.txt`, `en-dict.txt` |
| `models/` | Mô hình viết tay đã huấn luyện `hw.bin` |
| `tools/` | Công cụ sinh dữ liệu: huấn luyện viết tay (`handwriting/`), tần suất từ (`wordfreq/`), emoji, phông, biểu tượng |
| `data/` | Dữ liệu huấn luyện: `raw/` (Unihan, Make Me a Hanzi, cjkvi-ids, NomDB, kho câu Leipzig), `fonts/` (Jigmo, LXGW WenKai), bộ chữ `charset*.txt`, nhật ký huấn luyện |

## Lõi Rust

Cần [Rust](https://rustup.rs). Trên Windows bật Smart App Control (chặn mọi .exe chưa ký số),
build sang WebAssembly và chạy bằng Node:

```sh
rustup target add wasm32-wasip1
cd core
cargo test --target wasm32-wasip1
cargo run --target wasm32-wasip1 --example demo -- chunom nawm "vieetj nam"
```

Máy khác thì bỏ `--target wasm32-wasip1`.

## Bàn phím Android

Cần Android Studio (JDK đi kèm), SDK 36, NDK 28.2 và target Rust
`aarch64-linux-android`, `x86_64-linux-android`. Gradle tự build lõi Rust thành
`libhannom_jni.so`, chép từ điển `dict/`, phông `fonts/` và mô hình `models/hw.bin` vào ứng dụng.

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cd mobile/android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sau khi cài: mở ứng dụng "Nôm Na", làm 2 bước bật và chọn "Nôm Na - Bàn phím Hán Nôm".

Phông hiển thị `fonts/hannom.ttf` (MiSans L3) và `fonts/hannom-extra.ttf` (cắt từ Jigmo, CC0) không có trong
git; tải từ Release `fonts-v1` trước khi build: `gh release download fonts-v1 -R PDK05/NomnaKeyBoard-public -D fonts`
(xem [fonts/README.md](fonts/README.md)). Thiếu thì bàn phím dùng phông của máy.

APK cài thử: xem mục [Releases](https://github.com/PDK05/NomnaKeyBoard-public/releases).

## Giấy phép

Mã nguồn: [MIT](LICENSE). Dữ liệu và phông bên thứ ba trong `data/` giữ giấy phép của nguồn gốc
(Unihan, Make Me a Hanzi, cjkvi-ids, NomDB, Leipzig Corpora Collection, Jigmo, LXGW WenKai, MiSans).

## Huấn luyện lại

Dữ liệu nằm trong `data/`. Ba tệp câu lớn trong `data/raw/corpus/` nén `.xz` (GitHub giới hạn 100 MB/tệp),
giải nén trước khi chạy `tools/wordfreq`: `xz -dk data/raw/corpus/*/*.xz`. Các công cụ đọc dữ liệu ở `$HANNOM_DATA`
(mặc định `D:/hannom-data`), nên trỏ về thư mục này:

```sh
export HANNOM_DATA=$PWD/data
```

Các bước sinh mô hình viết tay: [models/README.md](models/README.md). Ảnh đã kết xuất (`data/cache/`) và
checkpoint (`data/runs/`) không đưa lên, chạy lại công cụ để sinh.
