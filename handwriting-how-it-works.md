# Mô hình nhận dạng chữ Hán Nôm viết tay: công cụ, dữ liệu và cách huấn luyện

Tài liệu này kể lại cách mô hình nhận dạng chữ Hán Nôm viết tay được làm: lấy công cụ và dữ liệu ở đâu, sinh dữ liệu
huấn luyện thế nào, huấn luyện ra sao, rồi xuất mô hình để chạy trên điện thoại.

## Tóm tắt

- **Không dùng mô hình có sẵn.** Đây là một CNN tự thiết kế, huấn luyện từ đầu bằng PyTorch (trọng số khởi tạo ngẫu
  nhiên, không có trọng số huấn luyện sẵn nào).
- **Không có mẫu viết tay thật.** Toàn bộ dữ liệu là chữ viết tay **giả**, sinh ra từ dữ liệu nét chữ và phông chữ in
  tải miễn phí trên mạng.
- Nhận **26.809 chữ** (chữ Nôm, chữ Hán dùng ở Việt Nam, chữ Hán phổ thông giản thể và phồn thể).
- Huấn luyện trên **một laptop có GPU NVIDIA GeForce RTX 3050 Laptop (4 GB VRAM)**, tổng khoảng 7 giờ qua ba lần.
- Mô hình xuất ra tệp `hw.bin` khoảng **13 MB** (int8), chạy bằng mã Rust tự viết, không cần mạng.

## 1. Công cụ

| Công cụ | Phiên bản | Dùng để |
| --- | --- | --- |
| Python | 3.12 | Viết toàn bộ phần chuẩn bị dữ liệu và huấn luyện (`tools/handwriting/`) |
| PyTorch (bản có CUDA 12.6) | 2.14 | Định nghĩa mô hình, huấn luyện trên GPU |
| NumPy | 2.5 | Xử lý nét chữ (toạ độ), mảng ảnh |
| Pillow (PIL) | 13.0 | Vẽ chữ từ phông ra ảnh |
| fontTools | 4.68 | Đọc bảng mã của phông để biết phông có chữ nào |
| Rust | ổn định | Chạy mô hình trên điện thoại (crate `handwriting/`) |

Python và PyTorch cài trong một môi trường ảo riêng (`venv`); bản PyTorch cài từ kho của PyTorch kèm CUDA để chạy GPU.

## 2. Dữ liệu nguồn (đều tải miễn phí)

| Dữ liệu | Lấy ở đâu | Dùng để |
| --- | --- | --- |
| **Unihan** (cơ sở dữ liệu chữ Hán của Unicode) | `Unihan.zip` trên unicode.org (https://www.unicode.org/Public/UCD/latest/ucd/) | Chọn bộ chữ: âm Việt (`kVietnamese`), chữ Việt Nam đệ trình (`kIRG_VSource`), chữ Trung thông dụng (`kTGH`), phồn thể (`kBigFive`); quan hệ dị thể |
| **Make Me a Hanzi** | https://github.com/skishore/makemeahanzi (`graphics.txt`, `dictionary.txt`) | Đường giữa của từng nét cho khoảng chín nghìn chữ Hán, theo đúng thứ tự viết |
| **cjkvi-ids** | https://github.com/cjkvi/cjkvi-ids (`ids.txt`) | Cấu tạo chữ (IDS): chữ ghép từ bộ kiện nào, theo kiểu nào (vd. 𠊛 = ⿰亻㝵) |
| **Jigmo** (Jigmo, Jigmo2, Jigmo3) | https://kamichikoichi.github.io/jigmo/ (chữ lấy từ GlyphWiki) | Phông phủ gần hết chữ Hán hiếm, kể cả chữ Nôm |
| **LXGW WenKai** | https://github.com/lxgw/LxgwWenKai | Phông nét bút lông, gần với chữ viết tay |
| **MiSans L3** | Phông MiSans của Xiaomi | Thêm một kiểu chữ in |
| Từ điển Nôm của dự án | `dict/nomna-dict.txt` | Bảo đảm mọi chữ trong từ điển đều có trong bộ chữ |

Tổng dữ liệu thô khoảng 450 MB, phông khoảng 130 MB, ảnh dựng sẵn (cache) khoảng 1,6 GB.

## 3. Chọn bộ chữ (`charset.py`)

Một chữ được đưa vào bộ chữ nếu thuộc ít nhất một nguồn ở bảng trên (Unihan, Make Me a Hanzi, từ điển Nôm), và **vẽ
được**: hoặc có phông hiển thị được (kiểm tra bằng fontTools), hoặc dựng được từ nét (mục 4a). Kết quả là tệp
`charset.txt`, mỗi dòng một chữ; thứ tự dòng là số thứ tự lớp của mô hình. Chữ mới thêm vào cuối, chữ cũ giữ nguyên chỗ,
để mô hình cũ huấn luyện tiếp được khi bộ chữ lớn lên (14.677 → 25.212 → 26.809 chữ).

## 4. Sinh dữ liệu huấn luyện

Mỗi lô huấn luyện 384 ảnh trộn hai nguồn: 60% từ nét chữ, 40% từ phông chữ in. Mọi ảnh đều là ảnh xám 64 × 64, mực
trắng trên nền đen, chữ co vào khung (lề 4 điểm ảnh), giữ tỉ lệ, đặt giữa.

### 4a. Từ nét chữ (`synth.py`, `feeder.py`)

1. **Dựng nét của chữ.** Chữ có trong Make Me a Hanzi thì lấy thẳng nét. Chữ không có (phần lớn chữ Nôm) thì **ghép từ
   bộ kiện** theo IDS: tách chữ thành cây cấu tạo (⿰ trái–phải, ⿱ trên–dưới, ⿴ bao quanh…), lấy nét của từng bộ kiện
   rồi đặt vào ô của nó; kích thước ô tính theo số nét của mỗi phần. Ví dụ 𠊛 = ⿰亻㝵: nét 亻 vào ô trái, nét 㝵 vào ô phải.
2. **Viết ẩu như người thật:**
   - đôi khi bỏ một chấm (ba chấm thuỷ chỉ viết hai);
   - gộp chuỗi chấm (氵, 灬) thành một nét zíc zắc hoặc gần thẳng;
   - nối liền các nét gần nhau như khi không nhấc bút (kiểu hành thư).
3. **Làm méo:** xoay ±9°, nghiêng, co giãn 85–115% mỗi chiều, mỗi nét lệch chỗ một chút, run tay (nhiễu rồi làm mượt),
   cong nhẹ toàn chữ, độ dày bút từ 1,6 đến 3,6 điểm ảnh.
4. **Vẽ ra ảnh:** vẽ ở độ phân giải gấp 4 rồi thu nhỏ để khử răng cưa. Cách vẽ này giống hệt cách vẽ lúc nhận dạng thật
   trên điện thoại, để ảnh lúc học và lúc dùng cùng một kiểu.

Việc sinh ảnh chạy trên CPU ở 5 tiến trình song song, đưa lô sẵn sàng cho GPU.

### 4b. Từ phông chữ in (`render_fonts.py`, hàm `warp` trong `trainer.py`)

1. Vẽ sẵn mọi chữ bằng mọi phông có chữ đó, lưu vào một tệp cache.
2. Lúc huấn luyện, ảnh chữ in bị **bóp méo ngay trên GPU**: xoay ±10°, nghiêng, co giãn, dời chỗ, một trường cong ngẫu
   nhiên mượt (nhiễu 4×4 phóng lên 64×64), và gần một nửa số ảnh được làm đậm nét (chữ in nét mảnh hơn bút).

Nguồn này giúp những chữ không dựng được từ nét (chỉ có trong phông) vẫn có mẫu học.

## 5. Mô hình (`trainer.py`)

CNN kiểu VGG thu gọn, phân loại ảnh:

```
ảnh 64×64 (1 kênh)
→ conv 32 → max-pool
→ conv 64 → max-pool
→ conv 128 → conv 128 → max-pool
→ conv 256 → conv 256 → max-pool
→ conv 384 → gộp trung bình còn 2×2 (giữ bố cục thô của chữ)
→ kết nối đầy đủ 1536 → 384 (BatchNorm, ReLU) → dropout 0,2
→ kết nối đầy đủ 384 → 26.809 lớp
```

- Mọi conv là 3×3, có đệm, kèm BatchNorm và ReLU.
- Khoảng **12,9 triệu tham số**, riêng lớp cuối khoảng 10,3 triệu (vì gần 27 nghìn lớp).
- Khoảng **150 triệu phép nhân–cộng** mỗi ảnh.
- Lớp 384 chiều cũng dùng làm "đặc trưng" của chữ (để so một nét viết với mẫu chữ đã lưu).

## 6. Huấn luyện (`train.py`, `trainer.py`)

**Cài đặt:**

- Hàm mất mát: cross-entropy, label smoothing 0,1.
- Tối ưu: AdamW, tốc độ học tối đa 2·10⁻³, weight decay 0,05.
- Lịch học one-cycle: 5% số bước đầu tăng dần, sau đó giảm dần về gần 0.
- Tính toán bfloat16 trên GPU, lô 384 ảnh.
- Tốc độ khoảng 900–970 ảnh mỗi giây trên RTX 3050 Laptop.
- Cứ 2.000 bước đo một lần và lưu điểm kiểm tra (`last.pt`), dừng giữa chừng thì chạy tiếp được.

**Lệnh chạy:**

```sh
cd tools/handwriting
python charset.py          # bộ chữ
python render_fonts.py     # dựng sẵn ảnh chữ in
python train.py --steps 30000 --batch 384 --workers 5 --name v1
python export.py $HANNOM_DATA/runs/v1/last.pt ../../models/hw.bin
```

**Ba lần huấn luyện:**

| Lần | Số chữ | Số bước | Số ảnh đã học | Thời gian | Cách bắt đầu |
| --- | --- | --- | --- | --- | --- |
| v1 | 14.677 | 30.000 | ≈ 11,5 triệu | ≈ 3,4 giờ | Từ đầu (ngẫu nhiên) |
| v2 | 25.212 | 15.000 | ≈ 5,8 triệu | ≈ 1,7 giờ | Từ v1 |
| v3 | 26.809 | 15.000 | ≈ 5,8 triệu | ≈ 1,7 giờ | Từ v2 (**bản đang dùng**) |

Khi bộ chữ lớn lên (v2, v3), mọi lớp lấy lại từ lần trước. Lớp cuối giữ nguyên hàng của chữ cũ; hàng của chữ mới khởi
đầu bằng **trung bình các hàng chữ cũ** cộng chút nhiễu, vì hàng khởi tạo ngẫu nhiên kiểu mặc định sẽ lấn át chữ đúng.

**Kết quả:** đo trên 4.096 ảnh chữ viết tay **giả** (cố định, sinh cùng cách): đúng ngay gợi ý đầu 99,8%, đúng trong 5
gợi ý đầu 100%. Con số này cao vì tập đo sinh cùng cách với tập học. **Độ chính xác với chữ viết tay thật chưa đo**; đó
là việc tiếp theo (thu nét viết thật làm bộ đo, rồi dùng nó để huấn luyện thêm).

## 7. Xuất mô hình (`export.py`)

1. **Gộp BatchNorm** vào trọng số lớp đứng trước (bớt một phép tính mỗi lớp lúc chạy).
2. **Lượng tử hoá int8 theo từng kênh ra:** mỗi kênh một hệ số tỉ lệ, sai số nhỏ, tệp nhỏ đi khoảng 4 lần so với float32.
3. Ghi ra định dạng nhị phân riêng `hw.bin`: phần đầu là cỡ ảnh, danh sách chữ (mã Unicode), sau đó từng lớp (conv,
   pool, kết nối đầy đủ) kèm trọng số. Tệp khoảng 13 MB.

## 8. Chạy mô hình (`handwriting/`, Rust)

- Mã Rust tự viết đọc `hw.bin` và tự làm các phép tính của mạng: conv 3×3, pool, kết nối đầy đủ int8. Không dùng
  TensorFlow Lite, ONNX hay thư viện học máy nào.
- Có bài kiểm tra so kết quả Rust với PyTorch trên cùng một ảnh, để chắc hai bên tính giống nhau.
- Nét viết (dãy toạ độ) được vẽ thành ảnh 64 × 64 đúng như lúc huấn luyện, rồi đưa vào mạng. Vì nhận theo **ảnh của cả
  chữ** chứ không theo thứ tự nét, viết sai thứ tự nét vẫn nhận được.

## Bản đồ mã nguồn

| Tệp | Làm gì |
| --- | --- |
| `tools/handwriting/common.py` | Đường dẫn dữ liệu, danh sách phông, đọc Make Me a Hanzi / IDS / bộ chữ |
| `tools/handwriting/charset.py` | Chọn bộ chữ |
| `tools/handwriting/synth.py` | Dựng nét, ghép bộ kiện, làm viết ẩu, làm méo, vẽ ảnh |
| `tools/handwriting/render_fonts.py` | Dựng sẵn ảnh chữ in từ phông |
| `tools/handwriting/feeder.py` | Tiến trình sinh lô dữ liệu song song |
| `tools/handwriting/trainer.py`, `train.py` | Mô hình, bóp méo chữ in trên GPU, vòng huấn luyện |
| `tools/handwriting/export.py` | Gộp BatchNorm, lượng tử hoá int8, ghi `hw.bin` |
| `handwriting/src/net.rs` | Đọc và chạy mô hình (Rust) |
| `handwriting/src/render.rs` | Vẽ nét thành ảnh 64 × 64 (giống `synth.py`) |
