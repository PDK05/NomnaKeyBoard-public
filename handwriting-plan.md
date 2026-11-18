# Kế hoạch cải thiện viết tay Hán Nôm

Lập 2026-11-18. Làm từng bước, đo trước và sau mỗi bước, người dùng duyệt từng bước trước khi làm.

## Vấn đề

- Viết nhiều hơn 2 chữ Hán Nôm liền một dòng thì chưa nhận được.
- Viết riêng 2 chữ đã hay bị gộp thành 1 chữ.

## Nguyên nhân (đọc từ code, chưa tái hiện trên điện thoại)

Lỗi nằm chủ yếu ở **bước tách dòng thành từng chữ** (`handwriting/src/line.rs`), không phải ở mô hình nhận một chữ.

1. **Mô hình không biết nói "đây không phải một chữ".** Mô hình (`models/hw.bin`, 26.809 chữ) học từ ảnh đúng một
   chữ in, làm méo cho giống viết tay. Đưa ảnh hai chữ dính nhau vào, nó vẫn tự tin đoán ra một chữ, vì chữ ghép
   trái–phải (⿰) trông y như hai chữ đặt cạnh nhau. Độ chính xác 99,8% (v3) là đo trên chữ giả, không phải chữ viết
   thật.
2. **Cách chấm điểm khi tách nghiêng về gộp.** Mỗi chữ cộng một khoản phạt cố định (`SPLIT_COST`), nên tách làm hai
   tốn gấp đôi, trong khi mô hình lại tự tin khi gộp.
3. **Nét chồng nhau theo chiều ngang không bao giờ tách được.** Hàm `columns` gộp mọi nét chạm nhau theo chiều ngang
   thành một cột từ đầu. Chữ sau lấn sang chữ trước một chút là hai chữ dính luôn. Viết 3 chữ trở lên trên bảng hẹp
   thì chữ phải sát nhau, nên rất dễ gặp.
4. **Không dùng thứ tự viết.** Người viết đi từ trái sang phải; bút nhảy hẳn sang phải là dấu hiệu rõ nhất của chữ
   mới, nhưng code bỏ qua.
5. **Không dùng từ điển.** Khi phân vân giữa 人㝵 và 𠊛, từ điển Nôm sẵn có (`dict/nomna-dict.txt`) không được dùng.
6. **Không có bộ đo trên chữ viết thật**, nên mọi lần chỉnh hằng số đều làm mò.

## Các bước

### Bước 0: Đo hiện trạng (không đổi gì)

- Người dùng viết thử các câu 1–5 chữ trên điện thoại để tái hiện lỗi, nhất là xem vì sao không lên được 3 chữ.
  Có thể còn nguyên nhân ở giao diện: nét tự mờ sau 4 giây rồi chốt chữ (`HandwritingView.VISIBLE_MS`), hoặc bảng
  viết hẹp.
- Thêm nút ẩn "Ghi mẫu kiểm tra" để lưu nét kèm chữ đúng. Người dùng viết khoảng 100–200 dòng (15–20 phút), dùng
  làm bộ đo thật.
- Công cụ sinh dòng giả 2–5 chữ (khoảng cách, chồng nét, cỡ chữ thay đổi) từ `tools/handwriting/synth.py`.
- Chạy bộ đo bằng WebAssembly (Smart App Control chặn .exe). Số đo: % dòng tách đúng số chữ, % chữ đúng, theo từng
  số chữ, và thời gian nhận dạng mỗi lần nhấc bút trên điện thoại.

### Bước 1: Sửa thuật toán tách (không huấn luyện lại)

- Cho phép chữ chồng nét một chút: chỉ gộp cột khi chồng nhiều, không gộp khi chỉ chạm mép.
- Dùng thứ tự nét: nét mới bắt đầu bên phải toàn bộ mực cũ thì rất có thể là chữ mới.
- Thay khoản phạt cố định bằng "độ rộng chữ mong đợi" ước theo chiều cao chữ, kèm so khe hở với các khe khác trong
  dòng.
- Mỗi thay đổi đo riêng, chỉ giữ cái nào có số tốt hơn.

### Bước 2: Dùng từ điển Nôm để chấm cách tách

- Chuỗi chữ có trong từ điển (hoặc cặp chữ hay đi cùng nhau) được cộng điểm. Giúp chọn đúng giữa 人㝵 và 𠊛, và chọn
  đúng chữ khi mô hình phân vân.

### Bước 3: Huấn luyện lại mô hình biết từ chối (GPU nhiều giờ)

- Thêm lớp "không phải chữ", học từ nửa chữ (bộ trái/phải cắt rời), hai chữ dính nhau và nét vụn, để cách gộp sai
  bị điểm thấp.
- Giới hạn của máy: tối đa 5 worker, batch 384, không đụng pagefile.
- Mô hình mới phải bằng hoặc hơn mô hình cũ trên bộ đo của Bước 0 thì mới thay.

### Bước 4: Giao diện viết nhiều chữ

- Xem lại thời gian nét mờ / chốt chữ và độ rộng bảng sao cho viết được 4–5 chữ thoải mái.
- Đo cách Gboard (bàn phím viết tay tiếng Trung) xử lý trên điện thoại trước rồi mới chỉnh. Phần này người dùng
  quyết.

## Ghi kết quả

Kết quả đo mỗi bước ghi vào bảng dưới (như nhật ký tốc độ ở PRD-mobile.md §5).

| Ngày | Bước | Thay đổi | Dòng tách đúng | Chữ đúng | Thời gian / nhấc bút | Giữ / bỏ |
| ---- | ---- | -------- | -------------- | -------- | -------------------- | -------- |
