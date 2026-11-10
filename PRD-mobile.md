# PRD – Bàn phím Hán Nôm (Mobile: iOS, Android)

## 1. Tổng quan

### 1.1 Mục tiêu

Bàn phím ảo cho điện thoại với trải nghiệm giống Gboard (bố cục, thanh gợi ý, nhấn giữ, từ điển
cá nhân), dùng chung lõi gõ với bản desktop để gõ **Quốc ngữ** (Telex) và **Hán Nôm** (gõ âm đọc,
chọn chữ trên thanh gợi ý). Hỗ trợ **Telex đầy đủ** và **Telex rút gọn**, và nhập từ điển riêng
dạng `chữ;âm`.

Tên ứng dụng: **Bàn phím Hán Nôm**. Không có ngôn ngữ nào tên "Hán Nôm": trên giao diện (phím cách,
cài đặt, nhãn subtype), ngôn ngữ của chế độ này ghi là **㗂越 (漢喃)**.

### 1.2 Đối tượng sử dụng

- Người học, nghiên cứu, sáng tác chữ Hán Nôm trên điện thoại.
- Người dùng bản desktop muốn mang từ điển riêng sang điện thoại.

### 1.3 Phạm vi

- Trong phạm vi: bàn phím Android và iOS kiểu Gboard; chế độ Quốc ngữ / Hán Nôm / Tiếng Anh;
Telex đầy đủ &amp; rút gọn; thanh gợi ý; từ điển riêng; học thói quen; ứng dụng cài đặt đi kèm.
- Ngoài phạm vi: gõ vuốt (glide typing), nhập giọng nói, GIF/sticker, dịch, tìm kiếm web,
đồng bộ đám mây, bàn phím máy tính bảng tách đôi. Không dùng tên, logo, hình ảnh của Gboard.

### 1.4 Kiến trúc


| Thành phần        | Công nghệ                                                 | Ghi chú                                            |
| ----------------- | --------------------------------------------------------- | -------------------------------------------------- |
| `core/`           | Rust (dùng chung với desktop)                             | Android: JNI viết tay (`android-ffi/`); iOS: C FFI |
| `mobile/android/` | Kotlin, `InputMethodService`, View thuần (không AndroidX) | minSdk 26                                          |
| `mobile/ios/`     | Swift, Custom Keyboard Extension + ứng dụng chủ (SwiftUI) | iOS 15+; dùng App Group chia sẻ từ điển            |


Quy tắc Telex, Telex rút gọn, chế độ Hán Nôm, xếp hạng và định dạng từ điển **giống hệt**
`PRD-desktop.md` (F1, F2, F3, F5, F6) – mọi thay đổi quy tắc làm ở `core/` để hai bản luôn khớp.

## 2. Chức năng

### F1. Bố cục bàn phím (clone Gboard)

- QWERTY 4 hàng + hàng dưới: `?123`, nút chế độ (`V`/`喃`/`E`), phím cách, `.`, `Enter`.
- Nhấn giữ phím hiện ký tự phụ (vd. giữ `a` → â ă á à ả ã ạ; bàn phím Quốc ngữ dùng bảng như Gboard tiếng Việt: nguyên âm không có hàng `a A`, phụ âm không có bảng, giữ `d` chỉ ra đ);
giữ hàng trên → số 1–0.
- Bàn phím số &amp; ký hiệu, dấu câu Hán (，。、「」！？) khi ở chế độ Hán Nôm.
- Phím Shift: chạm một lần viết hoa 1 chữ, chạm đúp khoá viết hoa; tự viết hoa đầu câu (tắt được).
- Giữ phím xoá để xoá liên tục; vuốt trái trên phím xoá để xoá cả từ.
- Vuốt ngang trên phím cách để di chuyển con trỏ.
- Phản hồi rung và âm thanh khi gõ (bật/tắt), xem trước phím khi nhấn.
- Chủ đề sáng / tối / theo hệ thống; chiều cao bàn phím chỉnh được (3 mức).

### F2. Gõ Quốc ngữ &amp; Hán Nôm

**Flow:**

1. Người dùng chạm phím; chuỗi đang gõ hiện gạch chân trong ô nhập (composing text).
2. Lõi xử lý theo kiểu gõ đang bật (Telex đầy đủ hoặc rút gọn) và chế độ (Quốc ngữ / Hán Nôm).
3. Thanh gợi ý (F3) cập nhật; người dùng chạm gợi ý hoặc phím cách để chèn.

**Quy tắc:**

- Telex đầy đủ ở chế độ Quốc ngữ: biến đổi trực tiếp như Unikey (`vieejt` → việt).
- Telex rút gọn: gõ chữ gốc (`nom`), gợi ý hiện các dạng có dấu / chữ Hán Nôm khớp.
- Hán Nôm gõ âm đọc bằng Telex tự do như Quốc ngữ: phím cách một lần là tách chữ (khung gõ hiện dấu cách),
lần nữa mới chọn gợi ý đầu; không có gợi ý thì chốt chữ Quốc ngữ đang gõ. Quốc ngữ: phím cách chốt từ.
- Ứng dụng đổi ô nhập hoặc con trỏ nhảy chỗ khác: chốt nguyên chuỗi đang gõ, không mất chữ.
- Ô nhập mật khẩu: tự chuyển chế độ Tiếng Anh, không lưu, không học.

### F3. Thanh gợi ý

- Dải nằm trên bàn phím, không cuộn; số ô và cỡ ô theo cụm chữ Hán dài nhất đang hiện:
toàn chữ đơn 6 ô, có từ kép 5 ô; 3–4 chữ 4 ô, tổng ≤ 11 chữ (4+4+2+1, 3+3+3+2); 5–6 chữ 3 ô,
tổng ≤ 11 chữ; cụm 7 chữ 3 ô (cụm 7 + một cụm ≤ 3 chữ + một chữ
đơn); 8–10 chữ 2 ô (cụm dài + một cụm ≤ 3 chữ); 11 chữ 2 ô (+ một chữ đơn); &gt; 11 chữ chỉ mình nó.
Ít gợi ý hơn số ngăn thì không giãn ra cả thanh: các ô rộng như khi đủ ngăn, xếp từ ngăn 1 bên
trái (một chữ đơn chiếm ngăn 1 trong 6), phần còn lại để trống. Mỗi lần chỉ một cụm dài (≥ 7). Lấy gợi ý theo thứ tự xếp hạng, cái nào không vừa khuôn thì xuống
bảng mở rộng; gợi ý đầu luôn trên thanh. Ô chữ Hán rộng theo số chữ; tràn thanh thì thu nhỏ đều
chữ cho vừa, tới 15sp; vẫn tràn thì gợi ý đầu đứng một mình, thu nhỏ tiếp cho vừa; từ 21 chữ trở
đi giữ nguyên cỡ chữ như lúc 21 chữ, thanh kéo ngang được. Chữ Latin không tính vào số
chữ, chỉ rộng vừa đủ cho chữ khỏi dính; đơn, kép, Latin hiện lẫn được. Nút hỏi thêm từ vào từ điển
không tính vào số ô (5 cụm 2 chữ vẫn đủ 5 ô), chỉ chiếm thêm bề rộng. Giữa các ô có vạch ngăn mảnh,
không chạm viền trên dưới.
- Nút mũi tên ở cuối dải mở **bảng mở rộng** dạng lưới, chiếm chỗ bàn phím, cuộn dọc; chỉ hiện các
gợi ý chưa có trên thanh.
- Gợi ý ghép tự động không giới hạn độ dài, kể cả cụm lặp một chữ (`nomnomnom…` → 喃喃喃…);
dài trên 11 chữ thì một mình nó trên thanh gợi ý.
- Khung gõ (pinyin) một dòng, dài quá thì ẩn phần đầu. Chuỗi phím đang gõ tối đa 80 phím, quá thì
bỏ phím và rung báo, để không treo bàn phím.
- Nhấn giữ một gợi ý: hiện âm đọc, "Xoá khỏi gợi ý" (với mục học được / từ điển riêng).
- Khi không gõ gì: thanh hiện nút nhanh – chuyển Telex đầy đủ ↔ rút gọn, mở từ điển, cài đặt,
dán từ bộ nhớ tạm.
- Chữ ngoài BMP (𡨸…) hiển thị bằng phông Nôm đóng gói trong ứng dụng; độ trễ cập nhật gợi ý
≤ 50 ms trên máy tầm trung.

### F4. Từ điển riêng (kiểu Gboard)

- Định dạng và validation giống `PRD-desktop.md` F6: mỗi dòng `chữ;âm đọc;độ phổ biến` (`喃;nôm;30`),
chữ nhiều âm thì mỗi âm một dòng, UTF-8, `#` chú thích. Học thói quen là tăng độ phổ biến ngay trong
từ điển như desktop F5; từ điển lưu khi đóng bàn phím.
- Từ điển đã lưu theo định dạng cũ (`chữ;âm1,âm2`) trong máy được đổi tên thành `dictionary-old.txt`,
bàn phím dùng từ điển mặc định mới.
- Một từ điển duy nhất như desktop F6: lần đầu là bản sao từ điển mặc định, mọi thay đổi ghi
thẳng vào đó.
- Ứng dụng cài đặt → "Từ điển": danh sách mỗi cặp chữ – âm một dòng kèm độ phổ biến, xếp a → z, có
tìm kiếm; nút `+` thêm mục gồm 3 ô "Chữ", "Âm đọc" (gõ được nhiều âm, ngăn cách bằng `,`), "Độ phổ
biến"; chạm một dòng để sửa (chữ, âm, độ phổ biến) / xoá. Quản lý, nhập (Gộp / Thay toàn bộ), tự gộp
từ điển mặc định khi cập nhật ứng dụng: như desktop F6.
- Nút "Từ điển mặc định": gộp lại mục mặc định còn thiếu, hoặc thay toàn bộ bằng từ điển mặc định.
- Nhập: chọn một hoặc nhiều tệp `.txt` từ bộ nhớ máy / Files / iCloud Drive / Google Drive; mở
hoặc chia sẻ một / nhiều tệp `.txt` vào ứng dụng (Share sheet / Intent). Các tệp được gộp tự
động vào từ điển; hiện xem trước từng tệp: số mục mới, trùng, lỗi (kèm số dòng) trước khi xác nhận.
- Xuất: tạo tệp `.txt` và mở hộp thoại chia sẻ.
- Thêm nhanh từ bàn phím: đang gõ kết thúc bằng `'`, từ đơn (`nom'`) hay cụm (`chu'nom'`), thì luôn
hỏi "Thêm … vào từ điển?" ở đầu thanh gợi ý, kể cả khi đã có gợi ý hay đã có trong từ điển (để thêm
chữ khác cùng âm đọc). Không có `'` cuối thì chỉ hỏi khi không có gợi ý và đang gõ một từ đơn
(không có `'` ở giữa) chưa có trong từ điển. Bấm vào mở ứng dụng với ô Âm đọc đã điền sẵn.
- Âm đọc điền sẵn chỉ tách âm tiết ở chỗ người dùng gõ `'` hoặc phím cách,
không tự tách: `chu'nom` → "chu nom", `chunom` → "chunom".
- Hán Nôm: Enter chèn đúng chữ Quốc ngữ đang hiện trong khung gõ; phím cách mới chọn chữ Hán.
- iOS: từ điển lưu trong App Group để keyboard extension đọc được; extension chỉ đọc, ứng dụng
chủ ghi.

### F5. Chuyển chế độ &amp; cài đặt

- Chạm nút chế độ ở hàng dưới: xoay vòng Quốc ngữ → Hán Nôm → Tiếng Anh; nhấn giữ hiện menu
chọn trực tiếp và chọn kiểu gõ đầy đủ / rút gọn.
- Phím quả địa cầu (iOS) / chuyển bàn phím (Android) giữ hành vi chuẩn của hệ điều hành.
- Ứng dụng cài đặt có: hướng dẫn bật bàn phím từng bước (iOS: bật "Cho phép truy cập đầy đủ"
chỉ cần cho rung/âm thanh, ghi rõ không gửi dữ liệu), kiểu đặt dấu cũ/mới, số gợi ý, học
thói quen (bật/tắt, xoá dữ liệu học), giao diện, rung/âm thanh.
- Cài đặt hỏng hoặc thiếu: dùng mặc định, bàn phím vẫn hoạt động.

### F6. Bảng emoji &amp; tìm emoji

- Hàng trên bảng emoji: nút quay lại (tròn), nút tìm, các nhóm (cuộn ngang), như Gboard.
- Tìm emoji theo tên và từ khoá CLDR (tiếng Việt, tiếng Anh; gõ không dấu được). Kết quả 2 hàng, cuộn
theo trang 7 cột; giữ ô để chọn màu da / giới tính, bản chọn chỉ được gõ ra, không đổi màu chung.
- Nhãn giao diện đổi theo bàn phím đang dùng (Tiếng Việt / Hán Nôm / Tiếng Anh). Chữ Hán Nôm do người
dùng quyết định; mục còn trống chờ bổ sung, tạm hiện tiếng Việt:


| Chỗ hiện                    | Tiếng Việt                     | Hán Nôm | Tiếng Anh                          |
| --------------------------- | ------------------------------ | ------- | ---------------------------------- |
| Nút tìm, chữ mờ trong ô tìm | Tìm kiếm                       | 尋檢      | Search                             |
| Tiêu đề ô tìm               | Tìm biểu tượng cảm xúc         | 尋表象感觸   | Search emoji                       |
| Tiêu đề nhóm gần đây        | Biểu tượng cảm xúc gần đây     | 表象感觸近低  | Recent emoji *(đề xuất)*           |
| Tiêu đề nhóm                | Mặt cười và biểu tượng cảm xúc | 𩈘唭表象感觸 | Smileys &amp; emotions *(đề xuất)* |
| Tiêu đề nhóm                | Người                          | 𠊛      | People *(đề xuất)*                 |
| Tiêu đề nhóm                | Động vật và thiên nhiên        | 動物吧天然   |                                    |
| Tiêu đề nhóm                | Thực phẩm và đồ uống           | 食品吧𣘊㕵  | Food &amp; drink *(đề xuất)*       |
| Tiêu đề nhóm                | Du lịch và địa danh            | 遊歷地名    | Travel &amp; places *(đề xuất)*    |
| Tiêu đề nhóm                | Hoạt động và sự kiện           | 活動吧事件   |                                    |
| Tiêu đề nhóm                | Đồ vật                         | 𣘊物     | Objects *(đề xuất)*                |
| Tiêu đề nhóm                | Biểu tượng                     | 表象      | Symbols *(đề xuất)*                |
| Tiêu đề nhóm                | Cờ                             | 旗       | Flags *(đề xuất)*                  |


- Trang biểu tượng chữ `:-)`, như Gboard: cao hơn trang emoji 136 dp (màn hình dọc), hàng trên là nút quay lại, nút
"gần đây" (icon đồng hồ) rồi tên các nhóm (cuộn ngang); mỗi nhóm một trang riêng, lưới 4 cột, không có tiêu đề.
Biểu tượng lấy từ Gboard (`tools/emoji/gen_emoticons_gboard.py`, 14 nhóm × 32). Tên nhóm tiếng Việt và tiếng Anh
theo Gboard; cột Hán Nôm chờ người dùng điền, còn trống thì tạm hiện tiếng Việt:


| Nhóm biểu tượng chữ | Tiếng Việt            | Hán Nôm | Tiếng Anh           |
| ------------------- | --------------------- | ------- | ------------------- |
| Classic             | Cổ điển               | 古典      | Classic             |
| Smiling             | Cười                  | 唭       | Smiling             |
| Love                | Tình yêu              | 情𢞅     | Love                |
| Hugging             | Ôm                    | 揞       | Hugging             |
| Flexing             | Gập cánh tay          | 岋𦑃𢬣   | Flexing             |
| Animals             | Động vật              | 動物      | Animals             |
| Surprise            | Ngạc nhiên            | 愕然      | Surprise            |
| Dancing             | Nhảy múa              | 𬦮𢷵    | Dancing             |
| Shruggie            | Nhún vai              | 撋𦠘     | Shruggie            |
| Table flip          | Lật bàn               | 搮盤      | Table flip          |
| Look of disapproval | Ánh nhìn không đồng ý | 映𥆾空同意  | Look of disapproval |
| Crying              | Khóc                  | 哭       | Crying              |
| Nervous             | Lo lắng               | 𢥈𢠯    | Nervous             |
| Pointers            | Con trỏ               | 𡥵𢸫    | Pointers            |


- Tên từng emoji (tiếng Việt, tiếng Anh theo CLDR, cột Hán Nôm tạm sinh tự động — **chưa thống nhất**):
[emoji-names.md](emoji-names.md).
- Gõ chữ Hán Nôm vào ô tìm: mỗi chữ được đổi ra mọi âm đọc của nó trong từ điển đang dùng (cả chữ người dùng tự
thêm), emoji khớp khi tên hay từ khoá có một chữ trùng một âm đọc đó (𩈘唭 tìm như "mặt cười"). Không dùng cột Hán
Nôm của emoji-names.md.

## 3. Yêu cầu phi chức năng

- Hiệu năng: bàn phím hiện lên ≤ 300 ms; mỗi phím ≤ 16 ms (không giật khung hình).
- Bộ nhớ: keyboard extension iOS ≤ 48 MB (giới hạn của hệ thống) – từ điển dùng định dạng
nhị phân ánh xạ bộ nhớ (mmap), không nạp toàn bộ vào RAM.
- Riêng tư: không yêu cầu quyền mạng; không gửi dữ liệu gõ; dữ liệu học chỉ lưu trên máy.
- Trợ năng: phím có nhãn cho TalkBack / VoiceOver; hỗ trợ cỡ chữ lớn trong ứng dụng cài đặt.
- Kiểm thử: unit test `core/` dùng chung; test UI bàn phím bằng Espresso (Android) và XCUITest
(iOS); kiểm thử thủ công trên Zalo, Messenger, Notes, Chrome/Safari.
- Phát hành: Google Play và App Store; ảnh, tên, biểu tượng là của dự án, không dùng thương hiệu
Google/Gboard.

## 4. Danh sách chức năng &amp; trạng thái


| STT | Chức năng                   | Mã  | Phụ thuộc    | Trạng thái                          |
| --- | --------------------------- | --- | ------------ | ----------------------------------- |
| 1   | Bindings `core/` (JNI)      | C1  | core desktop | \[x\] Xong                          |
| 2   | Bố cục bàn phím             | F1  | –            | \[\~\] Android                      |
| 3   | Gõ Quốc ngữ &amp; Hán Nôm   | F2  | C1, F1       | \[\~\] Android                      |
| 4   | Thanh gợi ý                 | F3  | F2           | \[\~\] Android                      |
| 5   | Từ điển riêng               | F4  | C1           | \[\~\] Android                      |
| 6   | Chuyển chế độ &amp; cài đặt | F5  | F2           | \[\~\] Android                      |
| 7   | Bảng emoji &amp; tìm emoji  | F6  | F1           | \[\~\] Android (thiếu nhãn Hán Nôm) |
| 8   | Bản Android                 | A1  | F1–F6        | \[\~\] Chạy được trên máy ảo        |
| 9   | Bản iOS                     | I1  | F1–F6        | \[ \] Chưa làm                      |
